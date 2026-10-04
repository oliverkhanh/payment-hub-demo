package com.paymenthub.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code")
    private String orderCode;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "total_amount")
    private long totalAmount;

    private String currency;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "hub_payment_ref")
    private String hubPaymentRef;

    @Column(name = "hub_idempotency_key")
    private String hubIdempotencyKey;

    @Column(name = "hub_checkout_url")
    private String hubCheckoutUrl;

    @Column(name = "hub_requested_at")
    private Instant hubRequestedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() { }

    public Order(String orderCode, String idempotencyKey, String currency) {
        this.orderCode = orderCode;
        this.idempotencyKey = idempotencyKey;
        this.currency = currency;
        this.status = OrderStatus.CREATED;
        this.totalAmount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
        this.totalAmount += item.lineTotal();
    }

    public void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Chuyển trạng thái không hợp lệ: " + status + " -> " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOrderCode() { return orderCode; }
    public OrderStatus getStatus() { return status; }
    public long getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getHubPaymentRef() { return hubPaymentRef; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderItem> getItems() { return items; }

    public void attachHubPayment(String paymentRef, String checkoutUrl) {
        this.hubPaymentRef = paymentRef;
        this.hubCheckoutUrl = checkoutUrl;
        this.hubRequestedAt = Instant.now();
        this.updatedAt = this.hubRequestedAt;
    }

    public String hubIdempotencyKeyOrCreate(java.util.function.Supplier<String> generator) {
        if (hubIdempotencyKey == null) {
            hubIdempotencyKey = generator.get();
            this.updatedAt = Instant.now();
        }
        return hubIdempotencyKey;
    }

    public void resetHubAttempt(String newIdempotencyKey) {
        this.hubIdempotencyKey = newIdempotencyKey;
        this.hubPaymentRef = null;
        this.hubCheckoutUrl = null;
        this.updatedAt = Instant.now();
    }

    public String getHubIdempotencyKey() { return hubIdempotencyKey; }
    public String getHubCheckoutUrl()    { return hubCheckoutUrl; }
    public Instant getHubRequestedAt()   { return hubRequestedAt; }
}
