package com.paymenthub.hub;

import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.domain.bank.BankPaymentService;
import com.paymenthub.hub.domain.bank.BankCallDecision;
import com.paymenthub.hub.domain.bank.StopReason;
import com.paymenthub.hub.infra.PaymentAttemptRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BankPaymentServiceTest extends AbstractIntegrationTest {
    @Autowired BankPaymentService bankPaymentService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAttemptRepository paymentAttemptRepository;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx() { return new TransactionTemplate(txManager); }

    private String newCheckoutToken() {
        var created = postPayment(UUID.randomUUID().toString(),
                createBody("ord_store_" + UUID.randomUUID(), 150000));
        String url = HmacAuthenticationTest.extract(created.getBody(), "checkout_url");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private Long paymentIdOf(String token) {
        return tx().execute(s -> paymentRepository.findByCheckoutToken(token).orElseThrow().getId());
    }

    private PaymentStatus statusOf(Long paymentId) {
        PaymentStatus status = tx().execute(s -> paymentRepository.findById(paymentId)
                .orElseThrow().getStatus());
        return status;
    }

    private long attemptCount(Long paymentId) {
        return tx().execute(s -> (long) paymentAttemptRepository.findAll().stream()
                .filter(a -> a.getPaymentId().equals(paymentId)).count());
    }

    @Test
    @DisplayName("Thắng CAS -> payment IN_PROGRESS + đúng 1 attempt, chưa có kết quả")
    void createsBothWrites() {
        String token = newCheckoutToken();
        Long id = paymentIdOf(token);

        var result = bankPaymentService.startBankPayment(token, "VCB");

        assertThat(result).isInstanceOf(BankCallDecision.Proceed.class);
        var proceed = (BankCallDecision.Proceed) result;
        assertThat(proceed.bankCode()).isEqualTo("VCB");
        assertThat(proceed.idempotencyKey()).startsWith("att_");

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.IN_PROGRESS);

        var attempt = tx().execute(s -> paymentAttemptRepository.findById(proceed.attemptId()).orElseThrow());
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.isPending()).isTrue();
    }

    @Test
    @DisplayName("Snapshot chụp TRƯỚC CAS nên đọc được merchant dù entity đã detach")
    void snapshotSurvivesTheClear() {
        var proceed = (BankCallDecision.Proceed) bankPaymentService.startBankPayment(newCheckoutToken(), "VCB");

        assertThat(proceed.paymentSnapshot().merchantName()).isNotBlank();
        assertThat(proceed.paymentSnapshot().paymentRef()).startsWith("pay_");
        assertThat(proceed.paymentSnapshot().amount()).isEqualTo(150000);
        assertThat(proceed.paymentSnapshot().currency()).isEqualTo("VND");
        assertThat(proceed.paymentSnapshot().checkoutExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("⭐ 16 luồng cùng bấm -> đúng 1 Created và đúng 1 dòng attempt")
    void onlyOneAttemptUnderConcurrency() throws Exception {
        String token = newCheckoutToken();
        Long id = paymentIdOf(token);

        int threads = 16;
        var pool = Executors.newFixedThreadPool(threads);
        var go = new CountDownLatch(1);
        var createdCount = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                if (bankPaymentService.startBankPayment(token, "VCB") instanceof BankCallDecision.Proceed) {
                    createdCount.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(createdCount.get()).isEqualTo(1);

        assertThat(attemptCount(id)).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("⭐ Transaction ngoài rollback -> MẤT CẢ HAI cú ghi, không còn gì mồ côi")
    void bothWritesRollBackTogether() {
        String token = newCheckoutToken();
        Long id = paymentIdOf(token);

        tx().executeWithoutResult(s -> {
            var r = bankPaymentService.startBankPayment(token, "VCB");
            assertThat(r).isInstanceOf(BankCallDecision.Proceed.class);
            s.setRollbackOnly();
        });

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.CREATED);
        assertThat(attemptCount(id)).isZero();
    }

    @Test
    @DisplayName("bank_code bịa / đang tắt -> Refused, KHÔNG đụng vào payment")
    void rejectsUntrustedBankCode() {
        String token = newCheckoutToken();
        Long id = paymentIdOf(token);

        for (String bad : List.of("HACKER_BANK", "MB", "vcb", "")) {
            assertThat(bankPaymentService.startBankPayment(token, bad))
                    .as("bank_code=%s", bad)
                    .isInstanceOf(BankCallDecision.Stop.class);
        }

        assertThat(statusOf(id)).isEqualTo(PaymentStatus.CREATED);
        assertThat(attemptCount(id)).isZero();
    }

    @Test
    @DisplayName("Bấm lần hai -> Refused kèm lý do đọc được, không tạo attempt thứ hai")
    void secondClickIsRefusedWithReason() {
        String token = newCheckoutToken();
        Long id = paymentIdOf(token);

        bankPaymentService.startBankPayment(token, "VCB");
        var second = bankPaymentService.startBankPayment(token, "VCB");

        assertThat(second).isInstanceOf(BankCallDecision.Stop.class);
        assertThat(((BankCallDecision.Stop) second).reason())
                .isEqualTo(StopReason.ALREADY_IN_PROGRESS);
        assertThat(attemptCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("Token không tồn tại -> Refused, không ném exception")
    void unknownTokenIsRefused() {
        assertThat(bankPaymentService.startBankPayment("ctk_khong_co_that", "VCB"))
                .isInstanceOf(BankCallDecision.Stop.class);
    }
}
