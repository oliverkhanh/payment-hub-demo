package com.paymenthub.bank.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TcbPaymentInitRequest(
        @JsonProperty("txnAmount") String txnAmount,
        @JsonProperty("ccy") String ccy,
        @JsonProperty("merchantName") String merchantName,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("notifyUrl") String notifyUrl,
        @JsonProperty("redirectUrl") String redirectUrl,
        @JsonProperty("expiryTime") long expiryTime
) { }
