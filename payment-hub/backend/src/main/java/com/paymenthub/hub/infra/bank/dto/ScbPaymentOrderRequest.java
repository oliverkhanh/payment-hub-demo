package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScbPaymentOrderRequest(
        @JsonProperty("Header") Header header,
        @JsonProperty("Data") Data data
) {
    public record Header(
            @JsonProperty("RequestID") String requestId,
            @JsonProperty("RequestTime") String requestTime,
            @JsonProperty("ChannelID") String channelId
    ) { }

    public record Data(
            @JsonProperty("Amt") String amt,
            @JsonProperty("Ccy") String ccy,
            @JsonProperty("MerchantNm") String merchantNm,
            @JsonProperty("CallbackURI") String callbackUri,
            @JsonProperty("ReturnURI") String returnUri,
            @JsonProperty("ExpiryTime") String expiryTime
    ) { }
}
