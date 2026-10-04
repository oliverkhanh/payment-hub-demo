package com.paymenthub.merchant.api.dto;

import com.paymenthub.merchant.domain.Product;

public record ProductResponse(
        Long id,
        String name,
        String description,
        long priceAmount,
        String currency,
        String imageEmoji) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(), p.getName(), p.getDescription(),
                p.getPriceAmount(), p.getCurrency(), p.getImageEmoji());
    }
}
