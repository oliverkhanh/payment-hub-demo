package com.paymenthub.merchant.infra.hub;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình tích hợp Payment Hub (xem application.yml, mục `hub`). */
@ConfigurationProperties(prefix = "hub")
public record HubProperties(
        String baseUrl,
        String apiKey,
        String apiSecret,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRetries,
        String returnUrlBase
) { }
