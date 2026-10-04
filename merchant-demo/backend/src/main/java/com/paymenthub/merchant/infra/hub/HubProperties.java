package com.paymenthub.merchant.infra.hub;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
