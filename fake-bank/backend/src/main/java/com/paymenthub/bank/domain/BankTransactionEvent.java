package com.paymenthub.bank.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BankTransactionEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("bank_code") String bankCode,
        @JsonProperty("bank_txn_id") String bankTxnId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("status") String status
) { }
