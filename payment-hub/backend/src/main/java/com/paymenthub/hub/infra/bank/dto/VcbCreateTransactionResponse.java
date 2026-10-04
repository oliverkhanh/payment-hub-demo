package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VcbCreateTransactionResponse(
        @JsonProperty("result_code") String resultCode,
        @JsonProperty("message") String message,
        @JsonProperty("bank_txn_id") String bankTxnId,
        @JsonProperty("redirect_url") String redirectUrl
) {
    public String resultCodeOrEmpty() {
        return resultCode == null ? "" : resultCode;
    }
}
