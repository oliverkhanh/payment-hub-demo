package com.paymenthub.hub.domain;

import com.paymenthub.hub.infra.IdempotencyRecordRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Quét payment hết hạn. Job nằm TRONG HUB, không ở merchant:
 * Hub sở hữu payment ⇒ Hub sở hữu vòng đời của nó.
 *
 * Trade-off (CLAUDE.md §1): production chạy nhiều instance nên cần distributed lock
 * (Redisson) hoặc leader election để hai instance không quét trùng. Ở đây chạy 1 instance
 * nên bỏ qua — và việc quét trùng cũng vô hại vì transitionTo(EXPIRED) chỉ hợp lệ từ
 * CREATED, lần thứ hai sẽ không tìm thấy dòng nào nữa.
 */
@Component
public class PaymentExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryJob.class);

    private final PaymentRepository payments;
    private final IdempotencyRecordRepository idempotencyRecords;

    public PaymentExpiryJob(PaymentRepository payments,
                            IdempotencyRecordRepository idempotencyRecords) {
        this.payments = payments;
        this.idempotencyRecords = idempotencyRecords;
    }

    @Scheduled(fixedDelayString = "${hub.jobs.expiry.fixed-delay-ms:30000}")
    @Transactional
    public void expireStalePayments() {
        List<Payment> expirable = payments.findExpirable(Instant.now());
        if (expirable.isEmpty()) return;

        expirable.forEach(p -> p.transitionTo(PaymentStatus.EXPIRED));
        log.info("Đã hết hạn {} payment", expirable.size());
    }

    /**
     * Dọn sổ idempotency quá TTL 24h.
     * Sau khi dọn, lớp chống trùng #1 mất hiệu lực với khoá đó — nhưng lớp #2
     * (partial unique trên order_code) vẫn canh, nên vẫn không tạo được payment trùng.
     */
    @Scheduled(fixedDelayString = "${hub.jobs.idempotency-cleanup.fixed-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredIdempotencyRecords() {
        int removed = idempotencyRecords.deleteExpired(Instant.now());
        if (removed > 0) log.info("Đã dọn {} bản ghi idempotency quá hạn", removed);
    }
}
