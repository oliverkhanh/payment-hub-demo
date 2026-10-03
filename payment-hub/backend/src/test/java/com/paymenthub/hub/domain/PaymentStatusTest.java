package com.paymenthub.hub.domain;

import com.paymenthub.hub.domain.bank.BankOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test cho state machine — thuần domain, không cần Spring (CLAUDE.md §5). */
class PaymentStatusTest {

    @Test
    @DisplayName("CREATED chỉ đi được tới IN_PROGRESS / EXPIRED / CANCELLED")
    void createdTransitions() {
        assertThat(PaymentStatus.CREATED.allowedNext()).containsExactlyInAnyOrder(
                PaymentStatus.IN_PROGRESS, PaymentStatus.EXPIRED, PaymentStatus.CANCELLED);
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("FAILED / EXPIRED / CANCELLED / CAPTURED là terminal — thử lại = payment MỚI")
    void terminalStates() {
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
        assertThat(PaymentStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(PaymentStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(PaymentStatus.CAPTURED.isTerminal()).isTrue();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("isActive() phải TRÙNG điều kiện của partial index uq_payment_active")
    void activeStatesMatchPartialIndex() {
        // V2__payments.sql: WHERE status IN ('CREATED', 'IN_PROGRESS')
        for (PaymentStatus status : PaymentStatus.values()) {
            boolean expected = status == PaymentStatus.CREATED || status == PaymentStatus.IN_PROGRESS;
            assertThat(status.isActive()).as("isActive cho %s", status).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("IN_PROGRESS lùi được về CREATED — cạnh LÙI duy nhất, chỉ cho ca NOT_SENT")
    void inProgressCanBeReleasedBackToCreated() {
        assertThat(PaymentStatus.IN_PROGRESS.allowedNext()).containsExactlyInAnyOrder(
                PaymentStatus.CAPTURED, PaymentStatus.FAILED, PaymentStatus.CREATED);

        Payment payment = samplePayment();
        payment.transitionTo(PaymentStatus.IN_PROGRESS);
        payment.releaseForRetry();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        // bankCode phải xoá: lần sau Ana được chọn lại ngân hàng khác.
        assertThat(payment.getBankCode()).isNull();
    }

    @Test
    @DisplayName("FAILED KHÔNG lùi về CREATED được — bank đã trả lời thì không mở lại")
    void failedCannotBeReleased() {
        Payment payment = samplePayment();
        payment.transitionTo(PaymentStatus.IN_PROGRESS);
        payment.transitionTo(PaymentStatus.FAILED);

        assertThatThrownBy(payment::releaseForRetry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED -> CREATED");
    }

    @Test
    @DisplayName("Chỉ NOT_SENT được mở payment ra lại; INDETERMINATE thì KHÔNG")
    void onlyNotSentReopensPayment() {
        assertThat(BankOutcome.NOT_SENT.neverReachedBank()).isTrue();
        // ⭐ Dòng quan trọng nhất file này: đoán sai ở đây = khách trả tiền hai lần.
        assertThat(BankOutcome.INDETERMINATE.neverReachedBank()).isFalse();

        // Và "mở lại" khác "đóng thất bại" — NOT_SENT không được đóng FAILED.
        assertThat(BankOutcome.NOT_SENT.rulesOutCharge()).isFalse();
        assertThat(BankOutcome.DECLINED.rulesOutCharge()).isTrue();
        assertThat(BankOutcome.BAD_REQUEST.rulesOutCharge()).isTrue();
        assertThat(BankOutcome.INDETERMINATE.rulesOutCharge()).isFalse();
    }

    @Test
    @DisplayName("Transition sai ném lỗi — không set status tuỳ tiện (CLAUDE.md #3)")
    void invalidTransitionIsRejected() {
        Payment payment = samplePayment();
        payment.transitionTo(PaymentStatus.CANCELLED);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.CAPTURED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED -> CAPTURED");
    }

    private static Payment samplePayment() {
        return new Payment(null, "ord_1", 1000L, "VND", "https://shop.example.com/r",
                null, null, "pay_test", "ctk_test", java.time.Instant.now().plusSeconds(900));
    }
}
