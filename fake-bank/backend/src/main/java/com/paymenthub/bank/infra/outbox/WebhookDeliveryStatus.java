package com.paymenthub.bank.infra.outbox;

public enum WebhookDeliveryStatus {
    DELIVERED,
    RETRYABLE,
    PERMANENT
}
