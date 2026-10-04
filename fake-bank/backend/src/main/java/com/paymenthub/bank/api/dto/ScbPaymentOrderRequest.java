package com.paymenthub.bank.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScbPaymentOrderRequest(
        @JsonProperty("Header") Header header,
        @JsonProperty("Data") Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(
            @JsonProperty("RequestID") String requestId,
            @JsonProperty("RequestTime") String requestTime,
            @JsonProperty("ChannelID") String channelId) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("Amt") String amt,
            @JsonProperty("Ccy") String ccy,
            @JsonProperty("MerchantNm") String merchantNm,
            @JsonProperty("CallbackURI") String callbackUri,
            @JsonProperty("ReturnURI") String returnUri,
            @JsonProperty("ExpiryTime") String expiryTime) { }
}
