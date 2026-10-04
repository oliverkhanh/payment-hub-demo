package com.paymenthub.hub.domain;

import java.util.Set;

public enum PaymentStatus {
    CREATED,

    IN_PROGRESS,

    CAPTURED,

    FAILED,

    EXPIRED,

    CANCELLED,

    AUTHORIZED,
    VOIDED,
    REFUNDED;

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case CREATED     -> Set.of(IN_PROGRESS, EXPIRED, CANCELLED);
            case IN_PROGRESS -> Set.of(CAPTURED, FAILED, CREATED, CANCELLED);
            default          -> Set.of();
        };
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    public boolean isActive() {
        return this == CREATED || this == IN_PROGRESS;
    }
}
