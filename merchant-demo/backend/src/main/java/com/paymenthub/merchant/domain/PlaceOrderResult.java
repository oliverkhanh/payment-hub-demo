package com.paymenthub.merchant.domain;

/**
 * Kết quả đặt hàng: {@code created=true} nếu vừa tạo mới (HTTP 201),
 * {@code created=false} nếu là idempotent replay — trả lại đơn đã tạo trước đó (HTTP 200).
 */
public record PlaceOrderResult(Order order, boolean created) { }
