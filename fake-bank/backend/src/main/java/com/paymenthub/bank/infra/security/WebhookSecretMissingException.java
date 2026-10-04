package com.paymenthub.bank.infra.security;

public class WebhookSecretMissingException extends RuntimeException {

    public WebhookSecretMissingException(String bankCode) {
        super("Chưa cấu hình bank.webhook-secrets." + bankCode);
    }
}
