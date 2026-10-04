package com.paymenthub.bank.infra.outbox;

import com.paymenthub.bank.domain.OutboxEvent;
import com.paymenthub.bank.domain.OutboxEventRecorded;
import com.paymenthub.bank.infra.security.BankWebhookSigner;
import com.paymenthub.bank.infra.security.SignedWebhook;
import com.paymenthub.bank.infra.security.WebhookSecretMissingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final BankWebhookSigner bankWebhookSigner;
    private final WebhookSender webhookSender;
    private final OutboxDeliveryService outboxDeliveryService;

    @Value("${bank.webhook.max-attempts:5}")
    private int maxAttempts;

    @Value("${bank.webhook.batch-size:20}")
    private int batchSize;

    @Value("${bank.webhook.lease-ms:30000}")
    private long leaseMs;

    @Value("${bank.webhook.backoff-base-ms:10000}")
    private long backoffBaseMs;

    @Value("${bank.webhook.backoff-max-ms:60000}")
    private long backoffMaxMs;

    @Value("${bank.webhook.backoff-jitter-ms:1000}")
    private long backoffJitterMs;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxEventRecorded(OutboxEventRecorded recorded) {
        log.info("[outbox] commit xong, đánh thức relay ngay: outboxId={} eventId={} bank={}",
                recorded.outboxId(), recorded.eventId(), recorded.bankCode());
        dispatchDueEvents();
    }

    @Scheduled(fixedDelayString = "${bank.outbox.sweep-interval-ms:10000}")
    public void sweepDueEvents() {
        log.debug("[outbox] quét định kỳ các dòng còn PENDING đã tới hạn");
        dispatchDueEvents();
    }

    private void dispatchDueEvents() {
        Instant now = Instant.now();
        List<OutboxEvent> outboxPendings =
                outboxDeliveryService.claimPendings(now, batchSize, now.plusMillis(leaseMs));

        if (outboxPendings.isEmpty()) {
            log.debug("[outbox] không có dòng nào tới hạn");
            return;
        }

        log.info("[outbox] tìm thấy {} dòng tới hạn", outboxPendings.size());

        for (OutboxEvent outboxPending : outboxPendings) {
            sendWebhook(outboxPending);
        }
    }

    private void sendWebhook(OutboxEvent outboxPending) {
        int attemptNo = outboxPending.getAttemptCount();

        log.info("[outbox] event {} bắt đầu lần thử {}", outboxPending.getEventId(), attemptNo);

        final SignedWebhook signed;
        try {
            signed = bankWebhookSigner.sign(outboxPending, Instant.now());
        } catch (WebhookSecretMissingException misconfigured) {
            log.error("[outbox] không ký được event {}: {}",
                    outboxPending.getEventId(), misconfigured.getMessage());
            return;
        }

        log.debug("[outbox] đã ký event {}: POST {} timestamp={} bodyBytes={} signature={}",
                outboxPending.getEventId(),
                signed.url(),
                signed.headers().getFirst(BankWebhookSigner.HEADER_TIMESTAMP),
                signed.body().length,
                signed.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE));

        recordOutcome(outboxPending, attemptNo, webhookSender.post(signed));
    }

    private void recordOutcome(OutboxEvent outboxPending, int attemptNo,
                               WebhookDelivery delivery) {
        if (delivery.isDelivered()) {
            recordSent(outboxPending, delivery);
            return;
        }

        if (delivery.isPermanent()) {
            log.error("[outbox] event {} bị từ chối vĩnh viễn ({}), retry là vô ích",
                    outboxPending.getEventId(), delivery.detail());
            recordFailed(outboxPending, delivery);
            return;
        }

        if (attemptNo >= maxAttempts) {
            log.error("[outbox] event {} hết {} lần thử, bỏ cuộc. Lỗi cuối: {}",
                    outboxPending.getEventId(), maxAttempts, delivery.detail());
            recordFailed(outboxPending, delivery);
            return;
        }

        Instant nextAttemptAt = Instant.now().plusMillis(
                backoffMillis(attemptNo, backoffBaseMs, backoffMaxMs, backoffJitterMs));

        outboxDeliveryService.recordRetry(outboxPending.getId(), nextAttemptAt, delivery.detail());

        log.warn("[outbox] event {} lỗi tạm ({}), lần thử {}/{}, hẹn lại lúc {}",
                outboxPending.getEventId(), delivery.detail(), attemptNo, maxAttempts,
                nextAttemptAt);
    }

    static long backoffMillis(int attemptNo, long baseMs, long maxMs, long jitterMs) {
        long exponential = baseMs * (1L << (attemptNo - 1));
        long capped = Math.min(exponential, maxMs);
        return capped + ThreadLocalRandom.current().nextLong(jitterMs);
    }

    private void recordSent(OutboxEvent outboxPending, WebhookDelivery delivery) {
        int settled = outboxDeliveryService.recordSent(outboxPending.getId(), Instant.now());
        if (settled != 1) {
            log.warn("[outbox] event {} đã được chốt bởi tiến trình khác, bỏ qua",
                    outboxPending.getEventId());
            return;
        }

        log.info("[outbox] event {} gửi thành công ({}), chuyển SENT",
                outboxPending.getEventId(), delivery.detail());
    }

    private void recordFailed(OutboxEvent outboxPending, WebhookDelivery delivery) {
        int settled = outboxDeliveryService.recordFailed(outboxPending.getId(), delivery.detail());
        if (settled != 1) {
            log.warn("[outbox] event {} đã được chốt bởi tiến trình khác, bỏ qua",
                    outboxPending.getEventId());
            return;
        }

        log.info("[outbox] event {} chuyển FAILED, last_error={}",
                outboxPending.getEventId(), delivery.detail());
    }
}
