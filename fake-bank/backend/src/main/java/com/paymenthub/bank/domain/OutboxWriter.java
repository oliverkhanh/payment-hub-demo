package com.paymenthub.bank.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.bank.infra.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxWriter {

    public static final String AGGREGATE_TYPE = "BankTransaction";

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public OutboxEvent write(BankTransaction transaction, String eventType,
                             BankTransactionStatus status, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();

        BankTransactionEvent body = new BankTransactionEvent(
                eventId.toString(),
                eventType,
                occurredAt.toString(),
                transaction.getBankCode(),
                transaction.getBankTxnRef(),
                transaction.getIdempotencyKey(),
                transaction.getAmount(),
                transaction.getCurrency(),
                status.name());

        OutboxEvent event = outboxRepository.save(new OutboxEvent(
                eventId,
                transaction.getBankCode(),
                AGGREGATE_TYPE,
                transaction.getBankTxnRef(),
                eventType,
                toFrozenJson(body),
                transaction.getCallbackUrl(),
                occurredAt));

        applicationEventPublisher.publishEvent(new OutboxEventRecorded(
                event.getId(), event.getEventId(), event.getBankCode()));

        return event;
    }

    private String toFrozenJson(BankTransactionEvent body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException(
                    "Không serialize được payload outbox cho " + body.bankTxnId(), cannotSerialize);
        }
    }
}
