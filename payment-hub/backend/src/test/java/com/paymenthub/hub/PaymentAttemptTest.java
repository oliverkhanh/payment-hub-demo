package com.paymenthub.hub;

import com.paymenthub.hub.domain.PaymentAttempt;
import com.paymenthub.hub.domain.PaymentRefGenerator;
import com.paymenthub.hub.infra.PaymentAttemptRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAttemptTest extends AbstractIntegrationTest {
    @Autowired PaymentRepository payments;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired PaymentRefGenerator ids;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx() { return new TransactionTemplate(txManager); }

    private Long newPaymentId() {
        var created = postPayment(UUID.randomUUID().toString(),
                createBody("ord_att_" + UUID.randomUUID(), 150000));
        String ref = HmacAuthenticationTest.extract(created.getBody(), "payment_ref");
        return payments.findByPaymentRef(ref).orElseThrow().getId();
    }

    private PaymentAttempt save(Long paymentId, int no, String bank, String key) {
        return tx().execute(s ->
                attempts.save(new PaymentAttempt(paymentId, no, bank, key)));
    }

    @Test
    @DisplayName("Attempt mới mang ý định, CHƯA có kết quả — mọi cột outcome còn NULL")
    void newAttemptHasNoOutcomeYet() {
        Long paymentId = newPaymentId();
        String key = ids.newBankIdempotencyKey();

        PaymentAttempt saved = save(paymentId, 1, "VCB", key);

        assertThat(saved.getAttemptNo()).isEqualTo(1);
        assertThat(saved.getBankCode()).isEqualTo("VCB");
        assertThat(saved.getIdempotencyKey()).isEqualTo(key);

        assertThat(saved.isPending()).isTrue();
        assertThat(saved.getOutcome()).isNull();
        assertThat(saved.getBankTxnId()).isNull();
        assertThat(saved.getRedirectUrl()).isNull();
        assertThat(saved.getHttpStatus()).isNull();
        assertThat(saved.getErrorCode()).isNull();
        assertThat(saved.getAnsweredAt()).isNull();

        assertThat(saved.getRequestedAt()).isNull();
        var fromDb = tx().execute(s -> attempts.findById(saved.getId()).orElseThrow());
        assertThat(fromDb.getRequestedAt()).isNotNull();
        assertThat(fromDb.isPending()).isTrue();
    }

    @Test
    @DisplayName("⭐ Khoá idempotency UNIQUE TOÀN CỤC — không payment nào dùng lại được")
    void idempotencyKeyIsGloballyUnique() {
        String key = ids.newBankIdempotencyKey();
        save(newPaymentId(), 1, "VCB", key);

        Long other = newPaymentId();
        assertThatThrownBy(() -> save(other, 1, "TCB", key))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Khoá sinh mỗi lần một khác, có tiền tố att_")
    void keysAreUniqueAndPrefixed() {
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 500; i++) {
            String key = ids.newBankIdempotencyKey();
            assertThat(key).startsWith("att_");
            assertThat(key.length()).isLessThanOrEqualTo(64);
            assertThat(seen.add(key)).as("khoá bị trùng: %s", key).isTrue();
        }
    }

    @Test
    @DisplayName("UNIQUE(payment_id, attempt_no) — không có hai attempt cùng số cho một payment")
    void attemptNumberIsUniquePerPayment() {
        Long paymentId = newPaymentId();
        save(paymentId, 1, "VCB", ids.newBankIdempotencyKey());

        assertThatThrownBy(() -> save(paymentId, 1, "TCB", ids.newBankIdempotencyKey()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Một payment thử nhiều bank khác nhau — bank_code thuộc về ATTEMPT, không thuộc payment")
    void samePaymentCanTryDifferentBanks() {
        Long paymentId = newPaymentId();
        save(paymentId, 1, "VCB", ids.newBankIdempotencyKey());
        save(paymentId, 2, "TCB", ids.newBankIdempotencyKey());

        var latest = tx().execute(s -> attempts.findTopByPaymentIdOrderByAttemptNoDesc(paymentId));
        assertThat(latest).isPresent();
        assertThat(latest.get().getAttemptNo()).isEqualTo(2);
        assertThat(latest.get().getBankCode()).isEqualTo("TCB");
    }

    @Test
    @DisplayName("Tra ngược từ khoá về attempt — đường duy nhất để đối soát khi timeout")
    void canLookUpByIdempotencyKey() {
        Long paymentId = newPaymentId();
        String key = ids.newBankIdempotencyKey();
        save(paymentId, 1, "ACB", key);

        var found = tx().execute(s -> attempts.findByIdempotencyKey(key));
        assertThat(found).isPresent();
        assertThat(found.get().getPaymentId()).isEqualTo(paymentId);
        assertThat(found.get().getBankCode()).isEqualTo("ACB");
    }
}
