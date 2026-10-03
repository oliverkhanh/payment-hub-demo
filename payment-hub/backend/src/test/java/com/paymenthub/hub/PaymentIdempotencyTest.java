package com.paymenthub.hub;

import com.paymenthub.hub.domain.PaymentExpiryJob;
import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.infra.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hai lớp chống trùng (docs/notes/inc3-data-model.md §3).
 *
 * ⭐ Bài học Increment 2: TEST TUẦN TỰ XANH KHÔNG CHỨNG MINH ĐƯỢC GÌ VỀ RACE.
 * Test tuần tự chỉ chạm nhánh "khoá đã COMPLETED"; phải bắn đồng thời mới vào
 * nhánh IN_PROGRESS và mới kiểm được hành vi của UNIQUE ở DB.
 */
class PaymentIdempotencyTest extends AbstractIntegrationTest {

    @Autowired PaymentRepository payments;
    @Autowired PaymentExpiryJob expiryJob;

    @Test
    @DisplayName("Lớp 1 — gửi lại y nguyên -> 200 + body GIỐNG HỆT lần đầu")
    void replayReturnsIdenticalBody() {
        String key = UUID.randomUUID().toString();
        String body = createBody("ord_replay", 355000);

        var first = postPayment(key, body);
        var second = postPayment(key, body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        // NGUYÊN VĂN, không dựng lại từ DB
        assertThat(second.getBody()).isEqualTo(first.getBody());

        assertThat(countPayments("ord_replay")).isEqualTo(1);
    }

    @Test
    @DisplayName("Lớp 1 — cùng khoá, body KHÁC -> 422 (gào lên, không giấu bug của merchant)")
    void sameKeyDifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();

        var first = postPayment(key, createBody("ord_reuse", 355000));
        var second = postPayment(key, createBody("ord_reuse", 1000));   // amount khác

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody()).contains("\"error_code\":\"idempotency_key_reuse\"");
    }

    @Test
    @DisplayName("⭐ Lớp 1 — 10 request ĐỒNG THỜI cùng khoá -> đúng 1 payment, 0 lỗi 500")
    void concurrentSameKeyCreatesExactlyOnePayment() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = createBody("ord_concurrent", 355000);
        int threads = 10;

        List<Integer> statuses = fireConcurrently(threads, () -> postPayment(key, body));

        long created = statuses.stream().filter(s -> s == 201).count();
        long replayedOrConflict = statuses.stream().filter(s -> s == 200 || s == 409).count();
        long serverErrors = statuses.stream().filter(s -> s >= 500).count();

        assertThat(created).as("chỉ MỘT thằng được tạo").isEqualTo(1);
        assertThat(replayedOrConflict).as("còn lại là replay (200) hoặc xung đột (409)")
                .isEqualTo(threads - 1);
        assertThat(serverErrors).as("KHÔNG được có 500 — đây là lỗi Inc 2 đã gặp").isZero();

        assertThat(countPayments("ord_concurrent")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ Lớp 2 — cùng đơn nhưng khoá KHÁC -> 409, lớp 1 bó tay ở đây")
    void sameOrderWithNewKeyIsRejected() {
        var first = postPayment(UUID.randomUUID().toString(), createBody("ord_dup_order", 355000));
        var second = postPayment(UUID.randomUUID().toString(), createBody("ord_dup_order", 355000));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody())
                .contains("\"error_code\":\"payment_already_exists\"")
                .contains("\"existing_payment_ref\":\"pay_");

        assertThat(countPayments("ord_dup_order")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ Lớp 2 — 10 request ĐỒNG THỜI cùng đơn, khoá KHÁC NHAU -> đúng 1 payment")
    void concurrentSameOrderDifferentKeys() throws Exception {
        String body = createBody("ord_concurrent_order", 355000);

        List<Integer> statuses = fireConcurrently(10,
                () -> postPayment(UUID.randomUUID().toString(), body));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s >= 500).count()).isZero();
        assertThat(countPayments("ord_concurrent_order")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ Partial index — sau khi EXPIRED thì tạo LẠI được (nếu UNIQUE cứng sẽ chặn)")
    void newPaymentAllowedAfterExpiry() {
        var first = postPayment(UUID.randomUUID().toString(),
                createBody("ord_retry_after_expiry", 355000));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        expireAllPaymentsOf("ord_retry_after_expiry");

        var second = postPayment(UUID.randomUUID().toString(),
                createBody("ord_retry_after_expiry", 355000));

        assertThat(second.getStatusCode()).as("""
                UNIQUE cứng sẽ trả 409 ở đây và khách VĨNH VIỄN không trả được đơn;
                partial index WHERE status IN ('CREATED','IN_PROGRESS') mới cho tạo lại""")
                .isEqualTo(HttpStatus.CREATED);

        assertThat(countPayments("ord_retry_after_expiry")).isEqualTo(2);
    }

    @Test
    @DisplayName("Job quét chuyển CREATED quá hạn -> EXPIRED")
    void expiryJobMarksStalePayments() {
        var created = postPayment(UUID.randomUUID().toString(), createBody("ord_job", 1000));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Đẩy hạn về quá khứ rồi chạy job
        payments.findAll().stream()
                .filter(p -> p.getOrderCode().equals("ord_job"))
                .forEach(p -> forceExpiry(p.getId()));
        expiryJob.expireStalePayments();

        assertThat(payments.findAll().stream()
                .filter(p -> p.getOrderCode().equals("ord_job"))
                .allMatch(p -> p.getStatus() == PaymentStatus.EXPIRED)).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Bắn N request CÙNG LÚC: dùng CountDownLatch để mọi thread khởi hành đồng thời. */
    private List<Integer> fireConcurrently(int threads,
                                           Callable<ResponseEntity<String>> call) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        var startGate = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return call.call().getStatusCode().value();
                }));
            }
            startGate.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var future : futures) statuses.add(future.get(30, TimeUnit.SECONDS));
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private long countPayments(String orderCode) {
        return payments.findAll().stream()
                .filter(p -> p.getOrderCode().equals(orderCode))
                .count();
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void forceExpiry(Long paymentId) {
        jdbc.update("update payments set checkout_expires_at = now() - interval '1 hour' where id = ?",
                paymentId);
    }

    private void expireAllPaymentsOf(String orderCode) {
        jdbc.update("update payments set status = 'EXPIRED' where order_code = ?", orderCode);
    }
}
