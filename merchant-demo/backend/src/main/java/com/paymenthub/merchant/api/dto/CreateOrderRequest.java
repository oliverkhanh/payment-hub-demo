package com.paymenthub.merchant.api.dto;

import java.util.List;

/**
 * Body checkout: client gửi các dòng hàng {productId, quantity}.
 * Server KHÔNG nhận giá từ client — tự tra giá trong DB (BR-M3 chống sửa giá).
 */
public record CreateOrderRequest(List<Line> items) {
    public record Line(Long productId, Integer quantity) { }
}
