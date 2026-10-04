package com.paymenthub.hub.domain.bank;

public enum BankOutcome {
    ACCEPTED,
    DECLINED,
    NOT_SENT,
    INDETERMINATE,
    BAD_REQUEST;

    public boolean neverReachedBank() {
        return this == NOT_SENT;
    }

    public boolean rulesOutCharge() {
        return this == DECLINED || this == BAD_REQUEST;
    }
}
