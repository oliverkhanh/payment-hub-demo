package com.paymenthub.hub.infra.bank.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AcbPaySessionRequest(
        @JsonProperty("AMT") String amt,
        @JsonProperty("CUR_CODE") String curCode,
        @JsonProperty("MER_NAME") String merName,
        @JsonProperty("REF_NO") String refNo,
        @JsonProperty("NOTIFY_URL") String notifyUrl,
        @JsonProperty("RET_URL") String retUrl,
        @JsonProperty("EXP_TS") String expTs
) { }
