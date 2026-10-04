package com.paymenthub.bank;

import com.paymenthub.bank.domain.OutboxEvent;
import com.paymenthub.bank.infra.OutboxRepository;
import com.paymenthub.bank.infra.outbox.OutboxDeliveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OutboxRelayClaimTest extends AbstractBankIntegrationTest {

    @Autowired OutboxDeliveryService outboxDeliveryService;
    @Autowired OutboxRepository outboxRepository;

    @Test
    @DisplayName("Claim: tăng attempt_count và đẩy next_attempt_at tới hạn lease")
    void claim_tang_dem_va_dat_lease() {
        OutboxEvent pending = pendingEvent(Instant.now().minusSeconds(1));
        Instant leaseUntil = Instant.now().plusSeconds(30);

        List<OutboxEvent> claimed =
                outboxDeliveryService.claimPendings(Instant.now(), 10, leaseUntil);

        assertThat(claimed).extracting(OutboxEvent::getId).contains(pending.getId());

        OutboxEvent sauClaim = outboxRepository.findById(pending.getId()).orElseThrow();
        assertThat(sauClaim.getAttemptCount()).isEqualTo(1);
        assertThat(sauClaim.getNextAttemptAt())
                .isCloseTo(leaseUntil, within(2, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("Lease giấu dòng khỏi lượt quét kế tiếp")
    void lease_giau_dong_khoi_luot_quet_sau() {
        OutboxEvent pending = pendingEvent(Instant.now().minusSeconds(1));
        outboxDeliveryService.claimPendings(Instant.now(), 10, Instant.now().plusSeconds(30));

        List<OutboxEvent> luotSau =
                outboxDeliveryService.claimPendings(Instant.now(), 10, Instant.now().plusSeconds(30));

        assertThat(luotSau).extracting(OutboxEvent::getId).doesNotContain(pending.getId());
    }

    @Test
    @DisplayName("batchSize chặn số dòng bốc mỗi lượt")
    void batch_size_chan_so_dong() {
        for (int i = 0; i < 7; i++) {
            pendingEvent(Instant.now().minusSeconds(1));
        }

        List<OutboxEvent> claimed =
                outboxDeliveryService.claimPendings(Instant.now(), 3, Instant.now().plusSeconds(30));

        assertThat(claimed).hasSize(3);
    }

    @Test
    @DisplayName("Dòng chưa tới hạn thì không bị bốc")
    void chua_toi_han_thi_khong_boc() {
        OutboxEvent tuongLai = pendingEvent(Instant.now().plusSeconds(600));

        List<OutboxEvent> claimed =
                outboxDeliveryService.claimPendings(Instant.now(), 10, Instant.now().plusSeconds(30));

        assertThat(claimed).extracting(OutboxEvent::getId).doesNotContain(tuongLai.getId());
    }

    @Test
    @DisplayName("recordSent / recordFailed là CAS: chỉ ăn khi còn PENDING")
    void ghi_ket_qua_la_cas() {
        OutboxEvent pending = pendingEvent(Instant.now().minusSeconds(1));

        assertThat(outboxDeliveryService.recordSent(pending.getId(), Instant.now())).isEqualTo(1);
        assertThat(outboxDeliveryService.recordSent(pending.getId(), Instant.now())).isZero();
        assertThat(outboxDeliveryService.recordFailed(pending.getId(), "muộn")).isZero();
    }

    private OutboxEvent pendingEvent(Instant readyAt) {
        return outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "VCB", "BankTransaction",
                "btx_" + UUID.randomUUID().toString().substring(0, 8),
                "BankTransaction.Succeeded", "{\"amount\":1}",
                "http://payment-hub:8080/bank-callbacks/VCB", readyAt));
    }
}
