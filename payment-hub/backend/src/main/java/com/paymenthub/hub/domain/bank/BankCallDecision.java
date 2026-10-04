package com.paymenthub.hub.domain.bank;

public sealed interface BankCallDecision {

    record Proceed(Long paymentId, Long attemptId, String bankCode, String idempotencyKey,
                   PaymentSnapshot paymentSnapshot) implements BankCallDecision { }

    record Stop(StopReason reason) implements BankCallDecision { }
}
