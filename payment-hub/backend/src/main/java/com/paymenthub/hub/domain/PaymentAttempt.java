package com.paymenthub.hub.domain;

import com.paymenthub.hub.domain.bank.BankOutcome;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Một lần Hub gọi sang Bank. Ghi TRƯỚC khi gọi, cập nhật SAU khi có trả lời.
 *
 * Không dùng @ManyToOne Payment: attempt chỉ cần payment_id. Giữ nó "mỏng" để lúc ghi
 * kết quả không phải nạp cả aggregate payment lên chỉ để sửa một dòng log.
 */
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

    /** updatable = false: gửi lại phải dùng LẠI khoá này, không được sinh mới. */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /** NULL = CHƯA CÓ TRẢ LỜI. Đây chính là trạng thái "in-doubt", không cần enum riêng. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
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
        // outcome để NULL: ta vừa ghi ý định, chưa hỏi ai cả.
    }

    /** Ghi lại câu trả lời adapter đã dịch. */
    public void complete(BankTransactionResult result) {
        this.outcome = result.outcome();
        this.bankTxnId = result.bankTxnId();
        this.redirectUrl = result.redirectUrl();
        this.httpStatus = result.httpStatus();
        this.errorCode = result.errorCode();
        this.answeredAt = Instant.now();
    }

    /** Chưa có trả lời ⇒ job đối soát phải đi hỏi Bank bằng idempotencyKey. */
    public boolean isPending() {
        return outcome == null;
    }

    /** Có giao dịch sẵn ở bank ⇒ Ana bấm lại thì dùng lại link, KHÔNG tạo giao dịch thứ hai. */
    public boolean wasAccepted() {
        return outcome == BankOutcome.ACCEPTED && redirectUrl != null;
    }

    public Long getId()               { return id; }
    public Long getPaymentId()        { return paymentId; }
    public Integer getAttemptNo()     { return attemptNo; }
    public String getBankCode()       { return bankCode; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BankOutcome getOutcome()   { return outcome; }
    public String getBankTxnId()      { return bankTxnId; }
    public String getRedirectUrl()    { return redirectUrl; }
    public Integer getHttpStatus()    { return httpStatus; }
    public String getErrorCode()      { return errorCode; }
    public Instant getRequestedAt()   { return requestedAt; }
    public Instant getAnsweredAt()    { return answeredAt; }
}
