package com.paymenthub.hub.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.paymenthub.hub.domain.Payment;

import java.time.Instant;

/**
 * Response trả cho merchant.
 *
 * KHÔNG trả: id (khoá nội bộ) · checkout_token (đã nằm trong checkout_url) · merchant_id.
 * checkout_url là OPAQUE — merchant dùng nguyên xi, không phân tích, không dựng lại
 * (nếu merchant tự ghép thì Hub đổi domain/path là mọi merchant vỡ).
 *
 * ECHO lại order_code/amount/currency có chủ đích: để merchant phát hiện NGAY lỗi hiểu sai
 * đơn vị tiền (gửi 3550.00 -> Hub đọc 3550, lệch 100 lần).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        String paymentRef,
        String status,
        String orderCode,
        Long amount,
        String currency,
        String checkoutUrl,
        Instant expiresAt,
        Instant createdAt,
        JsonNode metadata
) {
    public static PaymentResponse of(Payment payment, String checkoutBaseUrl, JsonNode metadata) {
        return new PaymentResponse(
                payment.getPaymentRef(),
                payment.getStatus().name(),
                payment.getOrderCode(),
                payment.getAmount(),
                payment.getCurrency(),
                checkoutBaseUrl + "/checkout/" + payment.getCheckoutToken(),
                payment.getCheckoutExpiresAt(),
                payment.getCreatedAt(),
                metadata);
    }
}
