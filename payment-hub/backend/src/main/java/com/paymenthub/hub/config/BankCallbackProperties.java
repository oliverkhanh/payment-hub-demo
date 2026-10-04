package com.paymenthub.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "hub.bank-callback")
public record BankCallbackProperties(Map<String, String> secrets) {

    public String secretFor(String bankCode) {
        return secrets == null || bankCode == null ? null : secrets.get(bankCode);
    }
}
