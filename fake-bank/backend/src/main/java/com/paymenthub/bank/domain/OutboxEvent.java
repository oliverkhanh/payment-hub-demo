package com.paymenthub.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "bank_code", nullable = false, updatable = false)
    private String bankCode;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "destination", nullable = false, updatable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEvent() { }

    public OutboxEvent(UUID eventId,
                       String bankCode,
                       String aggregateType,
                       String aggregateId,
                       String eventType,
                       String payload,
                       String destination,
                       Instant readyAt) {
        this.eventId = eventId;
        this.bankCode = bankCode;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.destination = destination;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = readyAt;
    }

    public void startAttempt(Instant leaseUntil) {
        this.attemptCount += 1;
        this.nextAttemptAt = leaseUntil;
    }
}
