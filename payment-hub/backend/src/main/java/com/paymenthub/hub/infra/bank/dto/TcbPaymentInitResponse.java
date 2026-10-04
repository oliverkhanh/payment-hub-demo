package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TcbPaymentInitResponse(
        @JsonProperty("status") String status,
        @JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("txnRef") String txnRef,
        @JsonProperty("paymentUrl") String paymentUrl
) {
    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    public String codeOrEmpty() {
        return code == null ? "" : code;
    }
}
