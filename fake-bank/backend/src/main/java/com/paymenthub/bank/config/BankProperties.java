package com.paymenthub.bank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "bank")
public record BankProperties(String publicBaseUrl, Integer hmacClockSkewSeconds,
                             Map<String, ApiClient> apiClients,
                             Map<String, String> webhookSecrets) {

    public record ApiClient(String key, String secret) { }

    public String pageUrl(String path) {
        return publicBaseUrl + path;
    }

    public ApiClient apiClient(String bankCode) {
        return apiClients == null ? null : apiClients.get(bankCode);
    }

    public String webhookSecret(String bankCode) {
        return webhookSecrets == null ? null : webhookSecrets.get(bankCode);
    }

    public Duration hmacClockSkew() {
        return Duration.ofSeconds(hmacClockSkewSeconds == null ? 300 : hmacClockSkewSeconds);
    }
}
