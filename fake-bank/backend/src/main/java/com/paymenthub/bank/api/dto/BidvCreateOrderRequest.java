package com.paymenthub.bank.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BidvCreateOrderRequest(
        @JsonProperty("orderAmount") long orderAmount,
        @JsonProperty("orderCurrency") String orderCurrency,
        @JsonProperty("merchant") String merchant,
        @JsonProperty("orderRef") String orderRef,
        @JsonProperty("ipnUrl") String ipnUrl,
        @JsonProperty("returnUrl") String returnUrl,
        @JsonProperty("expiry") String expiry
) { }
