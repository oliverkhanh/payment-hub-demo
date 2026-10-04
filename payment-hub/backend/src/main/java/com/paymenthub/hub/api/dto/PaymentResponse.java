package com.paymenthub.hub.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.paymenthub.hub.domain.Payment;

import java.time.Instant;

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
