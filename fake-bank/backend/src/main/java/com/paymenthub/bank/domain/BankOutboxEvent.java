package com.paymenthub.bank.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Một sự kiện chờ gửi sang Hub. Được ghi trong CÙNG transaction với việc trừ tiền.
 *
 * Dòng này là lời hứa: "đã có chuyện xảy ra, và Hub phải được biết". Gửi thành công
 * hay không là chuyện của worker — nhưng lời hứa thì không mất được, vì nó nằm cùng
 * transaction với sự thật mà nó mô tả.
 */
@Entity
@Table(name = "bank_outbox")
public class BankOutboxEvent {

    public enum Status { PENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hub nhận qua header Idempotency-Key để nhận ra callback trùng. */
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "bank_code", nullable = false, updatable = false)
    private String bankCode;

    @Column(name = "bank_txn_id", nullable = false, updatable = false)
    private String bankTxnId;

    @Column(name = "callback_url", nullable = false, updatable = false)
    private String callbackUrl;

    /**
     * Body NGUYÊN VĂN sẽ gửi đi. updatable = false: đã đóng băng thì không được sửa.
     * Worker ký trên đúng chuỗi này, không dựng lại từ bank_transactions —
     * dựng lại 10 phút sau có thể ra kết quả khác.
     */
    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected BankOutboxEvent() { }

    public BankOutboxEvent(String eventId, String bankCode, String bankTxnId,
                           String callbackUrl, String payload) {
        this.eventId = eventId;
        this.bankCode = bankCode;
        this.bankTxnId = bankTxnId;
        this.callbackUrl = callbackUrl;
        this.payload = payload;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
    }

    public Long getId()             { return id; }
    public String getEventId()      { return eventId; }
    public String getBankCode()     { return bankCode; }
    public String getBankTxnId()    { return bankTxnId; }
    public String getCallbackUrl()  { return callbackUrl; }
    public String getPayload()      { return payload; }
    public Status getStatus()       { return status; }
    public Integer getAttempts()    { return attempts; }
    public Instant getNextAttemptAt(){ return nextAttemptAt; }
    public String getLastError()    { return lastError; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getSentAt()      { return sentAt; }
}
