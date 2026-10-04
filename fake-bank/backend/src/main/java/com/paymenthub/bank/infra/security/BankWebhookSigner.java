package com.paymenthub.bank.infra.security;

import com.paymenthub.bank.config.BankProperties;
import com.paymenthub.bank.domain.OutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class BankWebhookSigner {

    public static final String HEADER_BANK_CODE = "X-Bank-Code";
    public static final String HEADER_SIGNATURE = "X-Bank-Signature";
    public static final String HEADER_TIMESTAMP = "X-Bank-Timestamp";
    public static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private static final String WEBHOOK_METHOD = "POST";

    private final BankProperties bankProperties;
    private final HmacSigner hmacSigner;

    public SignedWebhook sign(OutboxEvent event, Instant signedAt) {
        String webhookSecret = bankProperties.webhookSecret(event.getBankCode());
        if (webhookSecret == null) {
            throw new WebhookSecretMissingException(event.getBankCode());
        }

        URI url = URI.create(event.getDestination());
        byte[] body = event.getPayload().getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(signedAt.getEpochSecond());
        String idempotencyKey = event.getEventId().toString();

        String canonical = hmacSigner.canonicalString(
                WEBHOOK_METHOD, url.getPath(), timestamp, idempotencyKey, body);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HEADER_BANK_CODE, event.getBankCode());
        headers.set(HEADER_TIMESTAMP, timestamp);
        headers.set(HEADER_IDEMPOTENCY, idempotencyKey);
        headers.set(HEADER_SIGNATURE, hmacSigner.sign(webhookSecret, canonical));

        return new SignedWebhook(url, headers, body);
    }
}
