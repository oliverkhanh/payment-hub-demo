package com.paymenthub.hub.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

public record CreatePaymentRequest(

        @NotBlank(message = "order_code là bắt buộc.")
        @Size(max = 64, message = "order_code tối đa 64 ký tự.")
        String orderCode,

        @NotNull(message = "amount là bắt buộc.")
        @Positive(message = "amount phải > 0.")
        Long amount,

        @NotBlank(message = "currency là bắt buộc.")
        @Pattern(regexp = "[A-Z]{3}", message = "currency phải là 3 chữ IN HOA theo ISO 4217.")
        String currency,

        @NotBlank(message = "return_url là bắt buộc.")
        @Size(max = 2000)
        String returnUrl,

        @Size(max = 255, message = "description tối đa 255 ký tự.")
        String description,

        @Min(value = 300, message = "expires_in_seconds tối thiểu 300.")
        @Max(value = 3600, message = "expires_in_seconds tối đa 3600.")
        Integer expiresInSeconds,

        JsonNode metadata
) {
    public int expiresInSecondsOrDefault() {
        return expiresInSeconds != null ? expiresInSeconds : 900;
    }
}
