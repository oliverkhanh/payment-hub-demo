package com.paymenthub.bank.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VcbCreateTransactionRequest(
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("merchant_name") String merchantName,
        @JsonProperty("callback_url") String callbackUrl,
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("expires_at") String expiresAt
) { }
