package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AcbPaySessionResponse(
        @JsonProperty("error_no") Integer errorNo,
        @JsonProperty("error_desc") String errorDesc,
        @JsonProperty("txn_id") String txnId,
        @JsonProperty("pay_url") String payUrl
) {
    public int errorNoOrMissing() {
        return errorNo == null ? -1 : errorNo;
    }
}
