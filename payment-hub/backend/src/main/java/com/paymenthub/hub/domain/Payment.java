package com.paymenthub.hub.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Getter
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_ref", nullable = false, updatable = false)
    private String paymentRef;

    @Column(name = "checkout_token", nullable = false, updatable = false)
    private String checkoutToken;

    @Column(name = "checkout_expires_at", nullable = false)
    private Instant checkoutExpiresAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "order_code", nullable = false, updatable = false)
    private String orderCode;

    @Column(nullable = false, updatable = false)
    private Long amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "return_url", nullable = false, updatable = false)
    private String returnUrl;

    @Column(updatable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private String metadata;

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
}
