package com.paymenthub.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Đơn hàng phía merchant. Tạo khi checkout (BR-M4: bắt đầu ở CREATED = chờ thanh toán).
 * Tổng tiền LƯU lại (tính ở server, BR-M3); các dòng hàng là SNAPSHOT (BR-M2).
 */
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

    // ── Phase 2: tham chiếu sang Payment Hub ──
    @Column(name = "hub_payment_ref")
    private String hubPaymentRef;          // định danh vĩnh viễn Hub cấp, dùng đối soát

    /**
     * Khoá idempotency dùng khi gọi HUB — KHÁC orders.idempotency_key (khoá của chặng
     * trình duyệt -> merchant). Sinh MỘT LẦN rồi lưu: sinh mới mỗi lần gọi thì retry
     * sẽ tạo payment thứ hai.
     */
    @Column(name = "hub_idempotency_key")
    private String hubIdempotencyKey;

    @Column(name = "hub_checkout_url")
    private String hubCheckoutUrl;         // OPAQUE: dùng nguyên xi, không tự ghép

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
        this.totalAmount += item.lineTotal();   // tổng cộng dồn từ snapshot (server-side)
    }

    /** Chuyển trạng thái có kiểm soát (dùng ở Phase 2/3). */
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

    // ── Phase 2 ──

    /** Ghi lại kết quả khởi tạo thanh toán ở Hub. */
    public void attachHubPayment(String paymentRef, String checkoutUrl) {
        this.hubPaymentRef = paymentRef;
        this.hubCheckoutUrl = checkoutUrl;
        this.hubRequestedAt = Instant.now();
        this.updatedAt = this.hubRequestedAt;
    }

    /** Khoá gọi Hub phải ỔN ĐỊNH qua các lần retry ⇒ sinh một lần rồi giữ. */
    public String hubIdempotencyKeyOrCreate(java.util.function.Supplier<String> generator) {
        if (hubIdempotencyKey == null) {
            hubIdempotencyKey = generator.get();
            this.updatedAt = Instant.now();
        }
        return hubIdempotencyKey;
    }

    /** Lần thử thanh toán MỚI (payment cũ đã chết) ⇒ khoá MỚI, nếu không Hub trả replay cũ. */
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
