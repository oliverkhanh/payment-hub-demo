package com.paymenthub.hub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Sổ idempotency: trả lời BA câu hỏi khi một request tới
 *   ① khoá này đã thấy chưa?          -> UNIQUE(merchant_id, idempotency_key)
 *   ② có đúng CÙNG một request không? -> requestFingerprint
 *   ③ lần trước tới đâu, kết quả gì?  -> state + responseStatus + responseBody
 *
 * Nằm CÙNG database với payments để ghi trong CÙNG transaction -> không có dual-write.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    public enum State { IN_PROGRESS, COMPLETED }

    /** TTL của sổ. Sau đó dọn; lớp chống trùng #2 (partial unique) vẫn canh tiếp. */
    public static final java.time.Duration TTL = java.time.Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private Long merchantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** NGUYÊN VĂN response đã phát ra. Không dựng lại từ DB (trạng thái có thể đã đổi). */
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() { }

    public IdempotencyRecord(Long merchantId, String idempotencyKey, String endpoint,
                             String requestFingerprint) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.requestFingerprint = requestFingerprint;
        this.state = State.IN_PROGRESS;
        this.expiresAt = Instant.now().plus(TTL);
    }

    public void complete(int responseStatus, String responseBody, Long paymentId) {
        this.state = State.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.paymentId = paymentId;
        this.completedAt = Instant.now();
    }

    public boolean isCompleted()  { return state == State.COMPLETED; }
    public boolean isInProgress() { return state == State.IN_PROGRESS; }

    public boolean matchesFingerprint(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Long getPaymentId() { return paymentId; }
}
