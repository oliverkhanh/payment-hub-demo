package com.paymenthub.merchant.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "unit_price_amount")
    private long unitPriceAmount;

    private String currency;
    private int quantity;

    protected OrderItem() { }

    public OrderItem(Long productId, String productName, long unitPriceAmount, String currency, int quantity) {
        this.productId = productId;
        this.productName = productName;
        this.unitPriceAmount = unitPriceAmount;
        this.currency = currency;
        this.quantity = quantity;
    }

    void setOrder(Order order) { this.order = order; }

    public long lineTotal() { return unitPriceAmount * quantity; }

    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public long getUnitPriceAmount() { return unitPriceAmount; }
    public String getCurrency() { return currency; }
    public int getQuantity() { return quantity; }
}
