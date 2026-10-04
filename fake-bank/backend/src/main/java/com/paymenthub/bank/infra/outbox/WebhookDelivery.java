package com.paymenthub.bank.infra.outbox;

public record WebhookDelivery(WebhookDeliveryStatus status, String detail) {

    public static WebhookDelivery delivered(String detail) {
        return new WebhookDelivery(WebhookDeliveryStatus.DELIVERED, detail);
    }

    public static WebhookDelivery retryable(String detail) {
        return new WebhookDelivery(WebhookDeliveryStatus.RETRYABLE, detail);
    }

    public static WebhookDelivery permanent(String detail) {
        return new WebhookDelivery(WebhookDeliveryStatus.PERMANENT, detail);
    }

    public boolean isDelivered() {
        return status == WebhookDeliveryStatus.DELIVERED;
    }

    public boolean isPermanent() {
        return status == WebhookDeliveryStatus.PERMANENT;
    }
}
