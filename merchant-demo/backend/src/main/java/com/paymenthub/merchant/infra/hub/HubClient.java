package com.paymenthub.merchant.infra.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Client gọi Payment Hub. Đây là biên giữa HAI TỔ CHỨC nên:
 *   - xác thực bằng API key + HMAC (không phải JWT nội bộ),
 *   - CÓ timeout, CÓ retry (CLAUDE.md #6).
 *
 * ⭐ Retry giữ NGUYÊN Idempotency-Key. Sinh khoá mới khi retry = tạo payment thứ hai
 *   = khách bị trừ tiền hai lần. Khoá do tầng trên truyền xuống và đã được LƯU vào đơn.
 *
 * Chỉ retry nhóm KHÔNG CÓ KẾT LUẬN (timeout / lỗi mạng / 5xx). 4xx là kết luận rồi,
 * retry vô nghĩa. Circuit breaker: hoãn tới Increment 9, ghi chú ở đây.
 */
@Component
public class HubClient {

    private static final Logger log = LoggerFactory.getLogger(HubClient.class);

    private final HubProperties props;
    private final HubSigner signer;
    private final ObjectMapper json;
    private final RestClient http;

    public HubClient(HubProperties props, HubSigner signer, ObjectMapper json,
                     RestTemplateBuilder ignored) {
        this.props = props;
        this.signer = signer;
        this.json = json;

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.connectTimeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.readTimeoutMs()).toMillis());

        this.http = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** Kết quả gọi Hub: hoặc có body, hoặc là lỗi nghiệp vụ Hub trả về (problem+json). */
    public record HubResponse(int status, JsonNode body) {
        public boolean isSuccess() { return status >= 200 && status < 300; }
        public String errorCode()  {
            return body != null && body.hasNonNull("error_code") ? body.get("error_code").asText() : null;
        }
        public String text(String field) {
            return body != null && body.hasNonNull(field) ? body.get(field).asText() : null;
        }
    }

    public HubResponse createPayment(String idempotencyKey, Object requestBody) {
        byte[] raw = writeJson(requestBody);
        return callWithRetry("POST", "/v1/payments", idempotencyKey, raw);
    }

    public Optional<HubResponse> getPayment(String paymentRef) {
        HubResponse response = callWithRetry("GET", "/v1/payments/" + paymentRef, null, null);
        return response.status() == 404 ? Optional.empty() : Optional.of(response);
    }

    private HubResponse callWithRetry(String method, String path, String idempotencyKey, byte[] body) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= props.maxRetries() + 1; attempt++) {
            try {
                HubResponse response = call(method, path, idempotencyKey, body);
                // 5xx = Hub đang hỏng, CHƯA có kết luận -> đáng retry. 4xx thì không.
                if (response.status() >= 500 && attempt <= props.maxRetries()) {
                    backoff(attempt);
                    continue;
                }
                return response;
            } catch (ResourceAccessException networkOrTimeout) {
                // TIMEOUT = KHÔNG BIẾT, không phải thất bại. Hub có thể đã tạo payment rồi.
                // Retry an toàn CHÍNH VÌ gửi lại đúng Idempotency-Key cũ.
                lastFailure = networkOrTimeout;
                log.warn("Gọi Hub {} {} lỗi mạng/timeout (lần {}/{}): {}",
                        method, path, attempt, props.maxRetries() + 1, networkOrTimeout.getMessage());
                if (attempt <= props.maxRetries()) backoff(attempt);
            }
        }
        throw new HubUnavailableException("Không gọi được Payment Hub sau "
                + (props.maxRetries() + 1) + " lần thử.", lastFailure);
    }

    private HubResponse call(String method, String path, String idempotencyKey, byte[] body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String canonical = signer.canonicalString(method, path, timestamp, idempotencyKey, body);
        String signature = signer.sign(props.apiSecret(), canonical);

        var request = http.method(HttpMethod.valueOf(method))
                .uri(path)
                .header("X-Merchant-Key", props.apiKey())
                .header("X-Timestamp", timestamp)
                .header("X-Signature", signature)
                .header("X-Signature-Version", "v1");

        if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON);
            request.body(body);
        }

        var result = request
                .exchange((req, res) -> new HubResponse(
                        res.getStatusCode().value(), readJson(res.getBody().readAllBytes())), false);

        log.info("Hub {} {} -> {}", method, path, result.status());
        return result;
    }

    private void backoff(int attempt) {
        // exponential backoff + jitter: tránh cả đàn retry cùng nhịp (thundering herd)
        long delayMs = (long) (Math.pow(2, attempt - 1) * 200 + Math.random() * 100);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HubUnavailableException("Bị ngắt khi chờ retry", e);
        }
    }

    private byte[] writeJson(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được request gửi Hub", e);
        }
    }

    private JsonNode readJson(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        try {
            return json.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception notJson) {
            return null;
        }
    }

    /** Hub không trả lời được sau khi đã retry hết. KHÔNG kết luận là "thất bại". */
    public static class HubUnavailableException extends RuntimeException {
        public HubUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
