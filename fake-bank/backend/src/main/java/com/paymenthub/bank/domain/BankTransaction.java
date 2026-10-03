package com.paymenthub.bank.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "bank_transactions")
public class BankTransaction {

    public enum Status { PENDING, SUCCESS, DECLINED, CANCELLED }

    @Id
    @Column(name = "bank_txn_id")
    private String bankTxnId;

    @Column(name = "bank_code", nullable = false)
    private String bankCode;

    /** NULL tới khi khách nhập tài khoản (thành phần 2). */
    @Column(name = "account_code")
    private String accountCode;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "merchant_name", nullable = false)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "decline_reason")
    private String declineReason;

    /** Khoá Hub gửi sang. UNIQUE(bank_code, idempotency_key) ⇒ retry không đẻ giao dịch mới. */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "callback_url", nullable = false)
    private String callbackUrl;          // kênh A: bank -> Hub

    @Column(name = "return_url", nullable = false)
    private String returnUrl;            // kênh B: bank -> trình duyệt Ana

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected BankTransaction() { }

    public BankTransaction(String bankTxnId, String bankCode, long amount, String currency,
                           String merchantName, String idempotencyKey,
                           String callbackUrl, String returnUrl) {
        this.bankTxnId = bankTxnId;
        this.bankCode = bankCode;
        this.amount = amount;
        this.currency = currency;
        this.merchantName = merchantName;
        this.idempotencyKey = idempotencyKey;
        this.callbackUrl = callbackUrl;
        this.returnUrl = returnUrl;
        this.status = Status.PENDING;
    }

    public boolean isPending() { return status == Status.PENDING; }

    public String getBankTxnId()    { return bankTxnId; }
    public String getBankCode()     { return bankCode; }
    public String getAccountCode()  { return accountCode; }
    public Long getAmount()         { return amount; }
    public String getCurrency()     { return currency; }
    public String getMerchantName() { return merchantName; }
    public Status getStatus()       { return status; }
    public String getDeclineReason(){ return declineReason; }
    public String getIdempotencyKey(){ return idempotencyKey; }
    public String getCallbackUrl()  { return callbackUrl; }
    public String getReturnUrl()    { return returnUrl; }
    public Instant getCreatedAt()   { return createdAt; }
}
