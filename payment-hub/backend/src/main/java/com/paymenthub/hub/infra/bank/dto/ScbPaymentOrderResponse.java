package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScbPaymentOrderResponse(
        @JsonProperty("Header") Header header,
        @JsonProperty("Data") Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(
            @JsonProperty("ResponseCode") String responseCode,
            @JsonProperty("ResponseDesc") String responseDesc
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("TransactionID") String transactionId,
            @JsonProperty("PaymentURL") String paymentUrl
    ) { }

    public String responseCodeOrEmpty() {
        return header == null || header.responseCode() == null ? "" : header.responseCode();
    }

    public String transactionId() {
        return data == null ? null : data.transactionId();
    }

    public String paymentUrl() {
        return data == null ? null : data.paymentUrl();
    }
}
