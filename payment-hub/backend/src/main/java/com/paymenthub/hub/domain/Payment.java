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
    public Instant getCreatedAt() { return createdAt; }
}
