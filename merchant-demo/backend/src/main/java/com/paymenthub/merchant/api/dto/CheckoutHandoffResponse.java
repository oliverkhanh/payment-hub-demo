package com.paymenthub.merchant.api.dto;

/**
 * Trả cho frontend để chuyển hướng khách sang trang thanh toán của Hub.
 * `reused = true` nghĩa là dùng lại link đã tạo trước đó, không tạo payment mới.
 */
public record CheckoutHandoffResponse(String orderCode, String paymentRef,
                                      String checkoutUrl, boolean reused) { }
