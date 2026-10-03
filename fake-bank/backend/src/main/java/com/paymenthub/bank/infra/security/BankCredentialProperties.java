package com.paymenthub.bank.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Credential mà TỪNG NGÂN HÀNG cấp cho Hub. Mỗi bank một bộ riêng — Hub phải đối xử
 * với VCB/TCB/ACB như 3 hệ thống độc lập (spec §2.3).
 *
 * ⚠️ DEV-ONLY: để trong config. Production: Secrets Manager.
 */
@ConfigurationProperties(prefix = "bank")
public record BankCredentialProperties(
        Map<String, BankHmacVerifier.BankCredential> credentials,
        Map<String, String> displayNames
) {
    public String displayName(String bankCode) {
        return displayNames == null ? bankCode : displayNames.getOrDefault(bankCode, bankCode);
    }

    public boolean supports(String bankCode) {
        return credentials != null && credentials.containsKey(bankCode);
    }
}
