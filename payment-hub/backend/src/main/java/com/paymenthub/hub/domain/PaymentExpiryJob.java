package com.paymenthub.hub.domain;

import com.paymenthub.hub.infra.IdempotencyRecordRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentExpiryJob {
    private final PaymentRepository payments;
    private final IdempotencyRecordRepository idempotencyRecords;

    @Scheduled(fixedDelayString = "${hub.jobs.expiry.fixed-delay-ms:30000}")
    @Transactional
    public void expireStalePayments() {
        List<Payment> expirable = payments.findExpirable(Instant.now());
        if (expirable.isEmpty()) return;

        expirable.forEach(p -> p.transitionTo(PaymentStatus.EXPIRED));
        log.info("Đã hết hạn {} payment", expirable.size());
    }

    @Scheduled(fixedDelayString = "${hub.jobs.idempotency-cleanup.fixed-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredIdempotencyRecords() {
        int removed = idempotencyRecords.deleteExpired(Instant.now());
        if (removed > 0) log.info("Đã dọn {} bản ghi idempotency quá hạn", removed);
    }
}
