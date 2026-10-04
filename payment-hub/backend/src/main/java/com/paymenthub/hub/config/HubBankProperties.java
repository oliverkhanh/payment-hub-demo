package com.paymenthub.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "hub")
public record HubBankProperties(Map<String, BankInfo> banks) {
    public record BankInfo(String displayName, boolean enabled, Integer order, String baseUrl,
                           String apiKey, String apiSecret,
                           Integer connectTimeoutMs, Integer readTimeoutMs, Integer maxRetries) {

        public int orderOrLast()    { return order == null ? Integer.MAX_VALUE : order; }
        public int connectTimeout() { return connectTimeoutMs == null ? 2000 : connectTimeoutMs; }
        public int readTimeout()    { return readTimeoutMs == null ? 5000 : readTimeoutMs; }
        public int retries()        { return maxRetries == null ? 2 : maxRetries; }
    }

    public record BankOption(String code, String displayName) { }

    public List<BankOption> enabledOptions() {
        if (banks == null) return List.of();
        return banks.entrySet().stream()
                .filter(e -> e.getValue().enabled())
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, BankInfo> e) -> e.getValue().orderOrLast())
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new BankOption(e.getKey(), e.getValue().displayName()))
                .toList();
    }

    public boolean isEnabled(String bankCode) {
        var info = bankCode == null || banks == null ? null : banks.get(bankCode);
        return info != null && info.enabled();
    }

    public BankInfo require(String bankCode) {
        var info = banks == null ? null : banks.get(bankCode);
        if (info == null) {
            throw new IllegalStateException("Thiếu cấu hình hub.banks." + bankCode);
        }
        return info;
    }
}
