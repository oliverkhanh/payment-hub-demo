package com.paymenthub.hub.domain;

import com.paymenthub.hub.domain.bank.BankOutcome;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private Integer attemptNo;

    @Column(name = "bank_code", nullable = false, updatable = false)
    private String bankCode;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16)
    private BankOutcome outcome;

    @Column(name = "bank_txn_id")
    private String bankTxnId;

    @Column(name = "redirect_url")
    private String redirectUrl;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    protected PaymentAttempt() { }

    public PaymentAttempt(Long paymentId, int attemptNo, String bankCode, String idempotencyKey) {
        this.paymentId = paymentId;
        this.attemptNo = attemptNo;
        this.bankCode = bankCode;
        this.idempotencyKey = idempotencyKey;
    }

    public void recordResult(BankTransactionResult result, Instant answeredAt) {
        this.outcome = result.outcome();
        this.bankTxnId = result.bankTxnId();
        this.redirectUrl = result.redirectUrl();
        this.httpStatus = result.httpStatus();
        this.errorCode = result.errorCode();
        this.answeredAt = answeredAt;
    }

    public boolean isPending() {
        return outcome == null;
    }

    public boolean isInDoubt() {
        return outcome == null || outcome == BankOutcome.INDETERMINATE;
    }
}
