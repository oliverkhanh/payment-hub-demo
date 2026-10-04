package com.paymenthub.hub.domain.bank;

public enum StopReason {
    BANK_NOT_AVAILABLE,
    PAYMENT_NOT_FOUND,
    CHECKOUT_EXPIRED,
    ALREADY_IN_PROGRESS,
    ALREADY_PAID,
    CANCELLED,
    EARLIER_ATTEMPT_FAILED,
    NO_LONGER_ACTIVE
}
