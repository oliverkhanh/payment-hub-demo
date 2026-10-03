package com.paymenthub.hub.domain;

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
