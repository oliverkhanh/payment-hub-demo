package com.paymenthub.bank.domain;

public record NewTransaction(String idempotencyKey, long amount, String currency,
                             String merchantName, String callbackUrl, String returnUrl) { }
