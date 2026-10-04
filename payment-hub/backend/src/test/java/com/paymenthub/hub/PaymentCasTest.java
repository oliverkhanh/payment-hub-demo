package com.paymenthub.hub;

import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.infra.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCasTest extends AbstractIntegrationTest {
    @Autowired PaymentRepository payments;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private Long newPaymentId(String orderCode) {
        var created = postPayment(UUID.randomUUID().toString(), createBody(orderCode, 150000));
        String ref = HmacAuthenticationTest.extract(created.getBody(), "payment_ref");
        return payments.findByPaymentRef(ref).orElseThrow().getId();
    }

    private PaymentStatus statusOf(Long id) {
        return tx().execute(s -> payments.findById(id).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Lần đầu sửa 1 dòng và status thành IN_PROGRESS")
    void firstCallWins() {
        Long id = newPaymentId("ord_cas_first_" + UUID.randomUUID());

        int rows = tx().execute(s -> payments.markInProgress(id, Instant.now()));

        assertThat(rows).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Gọi lần hai sửa 0 dòng — 0 là KẾT QUẢ nghiệp vụ, không phải lỗi")
    void secondCallChangesNothing() {
        Long id = newPaymentId("ord_cas_second_" + UUID.randomUUID());

        assertThat((int) tx().execute(s -> payments.markInProgress(id, Instant.now()))).isEqualTo(1);
        assertThat((int) tx().execute(s -> payments.markInProgress(id, Instant.now()))).isEqualTo(0);

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("⭐ 16 tab bấm CÙNG LÚC — đúng MỘT tab thắng")
    void onlyOneConcurrentCallerWins() throws Exception {
        Long id = newPaymentId("ord_cas_race_" + UUID.randomUUID());

        int threads = 16;
        var pool = Executors.newFixedThreadPool(threads);
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var winners = new AtomicInteger();

        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                int rows = tx().execute(s -> payments.markInProgress(id, Instant.now()));
                if (rows == 1) winners.incrementAndGet();
                return rows;
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<Integer> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(winners.get()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Payment đã CANCELLED thì CAS không chạm được — mệnh đề WHERE giữ luật transition")
    void cannotRevivePaymentThatLeftCreated() {
        Long id = newPaymentId("ord_cas_cancelled_" + UUID.randomUUID());

        tx().executeWithoutResult(s ->
                payments.findById(id).orElseThrow().transitionTo(PaymentStatus.CANCELLED));

        assertThat((int) tx().execute(s -> payments.markInProgress(id, Instant.now()))).isEqualTo(0);
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("Id không tồn tại -> 0 dòng, không ném exception")
    void unknownIdIsNotAnError() {
        assertThat((int) tx().execute(s -> payments.markInProgress(-1L, Instant.now()))).isZero();
    }
}
