package com.paymenthub.hub.infra.bank;

import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.config.HubBankProperties.BankInfo;
import com.paymenthub.hub.infra.security.HmacSigner;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BankHttpClient {

    public static final String HEADER_KEY = "X-Hub-Key";
    public static final String HEADER_SIGNATURE = "X-Hub-Signature";
    public static final String HEADER_TIMESTAMP = "X-Hub-Timestamp";
    public static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private static final SecureRandom JITTER = new SecureRandom();

    private final HubBankProperties hubBankProperties;
    private final HmacSigner hmacSigner;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Map<String, RestClient> restClientsByBankCode = new ConcurrentHashMap<>();

    public enum Transport { ANSWERED, NOT_SENT, NO_ANSWER }

    public record Response(Integer status, String body, Transport transport, String failureReason) {
        public boolean answered()  { return transport == Transport.ANSWERED; }
        public boolean notSent()   { return transport == Transport.NOT_SENT; }
        public boolean noAnswer()  { return transport == Transport.NO_ANSWER; }
        public boolean ok()        { return status != null && status >= 200 && status < 300; }
        public boolean serverErr() { return status != null && status >= 500; }
    }

    public Response post(String bankCode, String url, MediaType contentType,
                         byte[] body, String idempotencyHeader) {

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(bankCode);

        if (!breaker.tryAcquirePermission()) {
            log.warn("[{}] circuit breaker ĐANG MỞ — không gửi, trả NOT_SENT ngay", bankCode);
            return new Response(null, null, Transport.NOT_SENT, "circuit_open");
        }

        long startedAt = System.nanoTime();
        Response response = postThroughRetries(bankCode, url, contentType, body, idempotencyHeader);
        recordToBreaker(breaker, bankCode, response, System.nanoTime() - startedAt);

        return response;
    }

    private void recordToBreaker(CircuitBreaker breaker, String bankCode, Response response,
                                 long elapsedNanos) {
        if (response.answered() && !response.serverErr()) {
            breaker.onSuccess(elapsedNanos, TimeUnit.NANOSECONDS);
            return;
        }

        String reason = response.answered()
                ? "HTTP " + response.status()
                : response.transport() + ":" + response.failureReason();

        breaker.onError(elapsedNanos, TimeUnit.NANOSECONDS,
                new BankUnavailableException(bankCode, reason));
    }

    private Response postThroughRetries(String bankCode, String url, MediaType contentType,
                                        byte[] body, String idempotencyHeader) {

        BankInfo bank = hubBankProperties.require(bankCode);
        int maxSends = bank.retries() + 1;
        Response last = null;

        for (int send = 1; send <= maxSends; send++) {
            last = sendOnce(bank, bankCode, url, contentType, body, idempotencyHeader);

            if (last.answered() && !last.serverErr()) {
                return last;
            }
            if (send < maxSends) {
                log.warn("[{}] lần gửi {}/{} chưa có kết quả dứt khoát ({}), sẽ thử lại",
                        bankCode, send, maxSends,
                        last.answered() ? "HTTP " + last.status()
                                        : last.transport() + ":" + last.failureReason());
                sleepBackoff(send);
            }
        }
        return last;
    }

    private Response sendOnce(BankInfo bank, String bankCode, String url,
                              MediaType contentType, byte[] body, String idempotencyHeader) {

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String path = URI.create(url).getRawPath();
        String canonical = hmacSigner.canonicalString("POST", path, timestamp, idempotencyHeader, body);
        String signature = hmacSigner.sign(bank.apiSecret(), canonical);

        try {
            var spec = restClient(bankCode, bank).post()
                    .uri(url)
                    .contentType(contentType)
                    .header(HEADER_KEY, bank.apiKey())
                    .header(HEADER_TIMESTAMP, timestamp)
                    .header(HEADER_SIGNATURE, signature);

            if (idempotencyHeader != null) {
                spec = spec.header(HEADER_IDEMPOTENCY, idempotencyHeader);
            }

            return spec.body(body).exchange((request, response) ->
                    new Response(response.getStatusCode().value(),
                            response.bodyTo(String.class), Transport.ANSWERED, null));

        } catch (Exception problem) {
            Transport transport = classify(problem);
            log.warn("[{}] gọi {} thất bại ({}): {}", bankCode, path, transport,
                    problem.getClass().getSimpleName() + ": " + problem.getMessage());
            return new Response(null, null, transport, problem.getClass().getSimpleName());
        }
    }

    static Transport classify(Throwable error) {
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof HttpConnectTimeoutException) {
                return Transport.NOT_SENT;
            }
        }
        return Transport.NO_ANSWER;
    }

    private RestClient restClient(String bankCode, BankInfo bank) {
        return restClientsByBankCode.computeIfAbsent(bankCode, code -> {
            HttpClient jdkClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(bank.connectTimeout()))
                    .build();
            var factory = new JdkClientHttpRequestFactory(jdkClient);
            factory.setReadTimeout(Duration.ofMillis(bank.readTimeout()));
            log.info("[{}] RestClient: connect={}ms read={}ms retry={}",
                    code, bank.connectTimeout(), bank.readTimeout(), bank.retries());
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    private void sleepBackoff(int sendNo) {
        long delay = (long) (200 * Math.pow(2, sendNo - 1)) + JITTER.nextInt(100);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
