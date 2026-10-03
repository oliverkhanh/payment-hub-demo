package com.paymenthub.bank.infra.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Verify request Hub gửi sang. Ở biên NÀY, BANK là bên ra luật:
 * credential do Bank cấp cho Hub (ngược với Inc 3, nơi Hub cấp cho merchant).
 *
 * Chuỗi ký giống scheme của Hub để đỡ phải học hai thứ:
 *   METHOD \n PATH \n TIMESTAMP \n IDEMPOTENCY_KEY \n SHA256_HEX(BODY)
 *
 * ⚠️ DEV-ONLY: secret của từng bank để trong config. Production: Secrets Manager.
 */
@Component
public class BankHmacVerifier {

    public static final String HEADER_KEY       = "X-Hub-Key";
    public static final String HEADER_SIGNATURE = "X-Hub-Signature";
    public static final String HEADER_TIMESTAMP = "X-Hub-Timestamp";
    public static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** bankCode -> {apiKey, apiSecret} mà Bank đã cấp cho Hub. */
    private final Map<String, BankCredential> credentials;
    private final Duration clockSkew;

    public BankHmacVerifier(BankCredentialProperties props,
                            @Value("${bank.hmac.clock-skew-seconds:300}") long skewSeconds) {
        this.credentials = props.credentials();
        this.clockSkew = Duration.ofSeconds(skewSeconds);
    }

    public record BankCredential(String apiKey, String apiSecret) { }

    /** @return true nếu request hợp lệ. Không kể lý do ra ngoài (không giúp kẻ tấn công dò). */
    public boolean verify(String bankCode, String method, String path, String apiKey,
                          String timestamp, String idempotencyKey, byte[] body, String signature) {

        BankCredential credential = credentials.get(bankCode);
        if (credential == null || apiKey == null || !credential.apiKey().equals(apiKey)) return false;
        if (signature == null || timestamp == null) return false;
        if (!isFresh(timestamp)) return false;

        String canonical = String.join("\n",
                method.toUpperCase(),
                path,
                timestamp,
                idempotencyKey == null ? "" : idempotencyKey,
                sha256Hex(body));

        return constantTimeEquals(sign(credential.apiSecret(), canonical), signature);
    }

    private boolean isFresh(String rawTimestamp) {
        try {
            Instant sent = Instant.ofEpochSecond(Long.parseLong(rawTimestamp.trim()));
            return Duration.between(sent, Instant.now()).abs().compareTo(clockSkew) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String sha256Hex(byte[] body) {
        if (body == null || body.length == 0) return EMPTY_BODY_SHA256;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không ký được HMAC", e);
        }
    }

    /** Constant-time: String.equals thoát sớm ⇒ lộ số byte khớp ⇒ timing attack. */
    private boolean constantTimeEquals(String expected, String received) {
        return MessageDigest.isEqual(
                expected.toLowerCase().getBytes(StandardCharsets.UTF_8),
                received.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
