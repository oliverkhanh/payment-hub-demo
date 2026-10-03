package com.paymenthub.hub.domain;

import jakarta.persistence.*;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "merchants")
public class Merchant {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_ref", nullable = false, updatable = false)
    private String merchantRef;

    @Column(nullable = false)
    private String name;

    @Column(name = "api_key", nullable = false, updatable = false)
    private String apiKey;

    /**
     * ⚠️ DEV-ONLY: plaintext. Không hash được vì Hub phải TÍNH LẠI HMAC.
     * Production: AES + khoá ở KMS.
     */
    @Column(name = "api_secret", nullable = false)
    private String apiSecret;

    /** Khoá cũ còn hiệu lực trong cửa sổ chồng lấn khi xoay khoá. */
    @Column(name = "api_secret_previous")
    private String apiSecretPrevious;

    @Column(name = "previous_expires_at")
    private Instant previousExpiresAt;

    @Column(name = "allowed_return_domains", nullable = false)
    private String allowedReturnDomains = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() { }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** Các secret cần thử khi verify: khoá hiện hành, rồi khoá cũ nếu chưa hết cửa sổ. */
    public List<String> secretsToTry() {
        boolean previousStillValid = apiSecretPrevious != null
                && previousExpiresAt != null
                && previousExpiresAt.isAfter(Instant.now());
        return previousStillValid ? List.of(apiSecret, apiSecretPrevious) : List.of(apiSecret);
    }

    /**
     * Chống open redirect: chỉ cho redirect về domain merchant đã đăng ký.
     * Không whitelist ⇒ Hub thành bàn đạp phishing ("tôi đi từ trang thanh toán chính thống").
     */
    public boolean allowsReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) return false;
        final String host;
        try {
            URI uri = URI.create(returnUrl);
            host = uri.getHost();
            if (host == null) return false;
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("https") || scheme.equals("http"))) return false;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return Arrays.stream(allowedReturnDomains.split(","))
                .map(String::trim)
                .filter(d -> !d.isEmpty())
                .anyMatch(d -> host.equalsIgnoreCase(d) || host.endsWith("." + d));
    }

    public Long getId() { return id; }
    public String getMerchantRef() { return merchantRef; }
    public String getName() { return name; }
    public String getApiKey() { return apiKey; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
