package com.paymenthub.merchant.domain;

import java.util.Set;

public enum OrderStatus {
    CREATED,
    AWAITING_PAYMENT,
    PAID,
    FAILED,
    EXPIRED,
    CANCELLED;

    private Set<OrderStatus> next() {
        return switch (this) {
            case CREATED          -> Set.of(AWAITING_PAYMENT, FAILED, CANCELLED);
            case AWAITING_PAYMENT -> Set.of(PAID, FAILED, EXPIRED, CANCELLED);
            default               -> Set.of();
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return next().contains(target);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }
}
