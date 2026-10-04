package com.paymenthub.hub.domain.bank;

import java.time.Instant;

public record PaymentSnapshot(String paymentRef, long amount, String currency,
                              String merchantName, Instant checkoutExpiresAt) { }
