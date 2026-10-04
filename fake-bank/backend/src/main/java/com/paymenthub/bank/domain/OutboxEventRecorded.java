package com.paymenthub.bank.domain;

import java.util.UUID;

public record OutboxEventRecorded(Long outboxId, UUID eventId, String bankCode) { }
