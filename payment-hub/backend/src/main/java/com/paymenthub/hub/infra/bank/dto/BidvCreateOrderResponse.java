package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BidvCreateOrderResponse(
        @JsonProperty("rc") String rc,
        @JsonProperty("rd") String rd,
        @JsonProperty("orderId") String orderId,
        @JsonProperty("payUrl") String payUrl) {

    public String rcOrEmpty() {
        return rc == null ? "" : rc;
    }
}
