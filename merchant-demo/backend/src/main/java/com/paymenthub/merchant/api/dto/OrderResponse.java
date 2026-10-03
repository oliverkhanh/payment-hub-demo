package com.paymenthub.merchant.api.dto;

import com.paymenthub.merchant.domain.Order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String orderCode,
        String status,
        long totalAmount,
        String currency,
        String hubPaymentRef,
        Instant createdAt,
        List<Item> items) {

    public record Item(
            Long productId,
            String productName,
            long unitPriceAmount,
            String currency,
            int quantity,
            long lineTotal) { }

    public static OrderResponse from(Order o) {
        List<Item> items = o.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getProductName(),
                        i.getUnitPriceAmount(), i.getCurrency(), i.getQuantity(), i.lineTotal()))
                .toList();
        return new OrderResponse(
                o.getOrderCode(), o.getStatus().name(), o.getTotalAmount(),
                o.getCurrency(), o.getHubPaymentRef(), o.getCreatedAt(), items);
    }
}
