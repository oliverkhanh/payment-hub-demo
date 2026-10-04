package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VcbCreateTransactionRequest(
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("merchant_name") String merchantName,
        @JsonProperty("callback_url") String callbackUrl,
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("expires_at") String expiresAt
) { }
