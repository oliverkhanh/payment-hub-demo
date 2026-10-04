package com.paymenthub.hub.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    public enum State { IN_PROGRESS, COMPLETED }

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
}
