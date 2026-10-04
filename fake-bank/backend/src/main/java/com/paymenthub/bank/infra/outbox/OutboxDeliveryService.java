package com.paymenthub.bank.infra.outbox;

import com.paymenthub.bank.domain.OutboxEvent;
import com.paymenthub.bank.infra.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxDeliveryService {

    private final OutboxRepository outboxRepository;

    @Transactional
    public List<OutboxEvent> claimPendings(Instant now, int batchSize, Instant leaseUntil) {
        List<OutboxEvent> claimed = outboxRepository.lockPendings(now, batchSize);
        claimed.forEach(outboxPending -> outboxPending.startAttempt(leaseUntil));
        return claimed;
    }

    @Transactional
    public int recordSent(Long outboxId, Instant sentAt) {
        return outboxRepository.markSent(outboxId, sentAt);
    }

    @Transactional
    public int recordRetry(Long outboxId, Instant nextAttemptAt, String lastError) {
        return outboxRepository.scheduleRetry(outboxId, nextAttemptAt, lastError);
    }

    @Transactional
    public int recordFailed(Long outboxId, String lastError) {
        return outboxRepository.markFailed(outboxId, lastError);
    }
}
