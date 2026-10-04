package com.paymenthub.bank.infra.outbox;

import com.paymenthub.bank.infra.security.SignedWebhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Slf4j
@Component
public class WebhookSender {

    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;

    public WebhookSender(
            @Value("${bank.webhook.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${bank.webhook.read-timeout-ms:3000}") long readTimeoutMs) {

        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder().requestFactory(factory).build();
        log.info("[webhook] connect={}ms read={}ms", connectTimeoutMs, readTimeoutMs);
    }

    public WebhookDelivery post(SignedWebhook signed) {
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(signed.url())
                    .headers(headers -> headers.addAll(signed.headers()))
                    .body(signed.body())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, errorResponse) -> { })
                    .toEntity(String.class);

            return classify(response.getStatusCode());
        } catch (ResourceAccessException transportFailure) {
            return WebhookDelivery.retryable(transportDetail(transportFailure));
        }
    }

    static WebhookDelivery classify(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return WebhookDelivery.delivered("HTTP " + status.value());
        }
        if (isPermanentRejection(status)) {
            return WebhookDelivery.permanent("HTTP " + status.value());
        }
        return WebhookDelivery.retryable("HTTP " + status.value());
    }

    private static boolean isPermanentRejection(HttpStatusCode status) {
        if (status.value() == HTTP_REQUEST_TIMEOUT || status.value() == HTTP_TOO_MANY_REQUESTS) {
            return false;
        }
        return status.is4xxClientError();
    }

    static String transportDetail(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String rootName = root.getClass().getSimpleName();
        return root.getMessage() == null ? rootName : rootName + ": " + root.getMessage();
    }
}
