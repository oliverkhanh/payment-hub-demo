package com.paymenthub.hub.domain;

public enum BankCallbackOutcome {
    CAPTURED,
    CANCELLED,
    ALREADY_SETTLED,
    ATTEMPT_NOT_FOUND,
    AMOUNT_MISMATCH,
    NOT_SETTLEABLE,
    UNSUPPORTED_EVENT
}
