package com.paymenthub.hub.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                          // ① nội bộ: KHÔNG bao giờ lộ ra ngoài

    @Column(name = "payment_ref", nullable = false, updatable = false)
    private String paymentRef;                // ② merchant: vĩnh viễn

    @Column(name = "checkout_token", nullable = false, updatable = false)
    private String checkoutToken;             // ③ Ana: vé vào cửa, có hạn

    @Column(name = "checkout_expires_at", nullable = false)
    private Instant checkoutExpiresAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "order_code", nullable = false, updatable = false)
    private String orderCode;                 // ④ của merchant

    @Column(nullable = false, updatable = false)
    private Long amount;                      // minor unit

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "return_url", nullable = false, updatable = false)
    private String returnUrl;

    @Column(updatable = false)
    private String description;

    // jsonb + String: phải nói rõ kiểu JDBC, nếu không driver gửi varchar
    // -> "column metadata is of type jsonb but expression is of type character varying".
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private String metadata;                  // JSON thô: Hub lưu & trả lại nguyên xi

    // ── Increment 4: ngân hàng Ana chọn ở trang checkout (NULL tới lúc bấm Thanh toán) ──
    @Column(name = "bank_code")
    private String bankCode;

    /** Giao dịch bên Bank. Hub coi là định danh ĐỐI SOÁT, không phân tích cấu trúc. */
    @Column(name = "bank_txn_id")
    private String bankTxnId;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Payment() { }

    public Payment(Merchant merchant, String orderCode, long amount, String currency,
                   String returnUrl, String description, String metadata,
                   String paymentRef, String checkoutToken, Instant checkoutExpiresAt) {
        this.merchant = merchant;
        this.orderCode = orderCode;
        this.amount = amount;
        this.currency = currency;
        this.returnUrl = returnUrl;
        this.description = description;
        this.metadata = metadata;
        this.paymentRef = paymentRef;
        this.checkoutToken = checkoutToken;
        this.checkoutExpiresAt = checkoutExpiresAt;
        this.status = PaymentStatus.CREATED;
    }

    /**
     * Chuyển trạng thái có kiểm tra (CLAUDE.md #3: không set status tuỳ tiện).
     * Ném IllegalStateException nếu transition không hợp lệ — gọi sai là lỗi lập trình,
     * không phải lỗi người dùng.
     */
    public void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Transition không hợp lệ: " + status + " -> " + next
                            + " (payment " + paymentRef + ")");
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public boolean isCheckoutExpired(Instant now) {
        return now.isAfter(checkoutExpiresAt);
    }

    /**
     * Nhả chỗ đã đặt: IN_PROGRESS -> CREATED.
     *
     * ⚠️ CHỈ được gọi khi BankOutcome.NOT_SENT — biết chắc request chưa hề tới bank.
     * Gọi nhầm ở ca timeout là tự mở đường cho khách trả tiền hai lần: bank đang giữ
     * một giao dịch, mà Hub lại cho tạo giao dịch thứ hai.
     *
     * Xoá bankCode vì lần sau Ana được chọn lại ngân hàng khác. attemptCount GIỮ NGUYÊN —
     * đó là lịch sử thật, không được xoá.
     */
    public void releaseForRetry() {
        transitionTo(PaymentStatus.CREATED);
        this.bankCode = null;
    }

    /**
     * Bank đã tạo giao dịch. Chỉ ghi định danh đối soát — KHÔNG đổi status:
     * tạo giao dịch chưa phải là trả tiền, Ana còn phải nhập tài khoản bên Bank.
     */
    public void attachBankTransaction(String bankTxnId) {
        this.bankTxnId = bankTxnId;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPaymentRef() { return paymentRef; }
    public String getCheckoutToken() { return checkoutToken; }
    public Instant getCheckoutExpiresAt() { return checkoutExpiresAt; }
    public Merchant getMerchant() { return merchant; }
    public String getOrderCode() { return orderCode; }
    public Long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getReturnUrl() { return returnUrl; }
    public String getDescription() { return description; }
    public String getMetadata() { return metadata; }
    public String getBankCode() { return bankCode; }
    public String getBankTxnId() { return bankTxnId; }
    public Integer getAttemptCount() { return attemptCount; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
}
