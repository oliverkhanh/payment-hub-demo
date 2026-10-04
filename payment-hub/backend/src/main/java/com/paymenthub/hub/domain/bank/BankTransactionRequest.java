package com.paymenthub.hub.domain.bank;

import java.time.Instant;

public record BankTransactionRequest(
        String paymentRef,
        long amount,
        String currency,
        String merchantName,
        String idempotencyKey,
        String callbackUrl,
        String returnUrl,
        Instant expiresAt
) { }
