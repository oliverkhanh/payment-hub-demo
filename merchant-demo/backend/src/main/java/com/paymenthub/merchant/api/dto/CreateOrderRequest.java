package com.paymenthub.merchant.api.dto;

import java.util.List;

public record CreateOrderRequest(List<Line> items) {
    public record Line(Long productId, Integer quantity) { }
}
