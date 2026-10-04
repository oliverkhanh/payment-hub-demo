package com.paymenthub.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;

    @Column(name = "price_amount")
    private long priceAmount;

    private String currency;

    @Column(name = "image_emoji")
    private String imageEmoji;

    private boolean active;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Product() { }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public long getPriceAmount() { return priceAmount; }
    public String getCurrency() { return currency; }
    public String getImageEmoji() { return imageEmoji; }
    public boolean isActive() { return active; }
}
