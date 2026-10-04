package com.paymenthub.bank.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "bank_transactions")
public class BankTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_code", nullable = false, updatable = false)
    private String bankCode;

    @Column(name = "bank_txn_ref", nullable = false, updatable = false)
    private String bankTxnRef;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "merchant_name", nullable = false, updatable = false)
    private String merchantName;

    @Column(name = "callback_url", nullable = false, updatable = false)
    private String callbackUrl;

    @Column(name = "return_url", nullable = false, updatable = false)
    private String returnUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BankTransactionStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected BankTransaction() { }

    public BankTransaction(String bankCode, String bankTxnRef, NewTransaction request) {
        this.bankCode = bankCode;
        this.bankTxnRef = bankTxnRef;
        this.idempotencyKey = request.idempotencyKey();
        this.amount = request.amount();
        this.currency = request.currency();
        this.merchantName = request.merchantName();
        this.callbackUrl = request.callbackUrl();
        this.returnUrl = request.returnUrl();
        this.status = BankTransactionStatus.PENDING;
    }
}
