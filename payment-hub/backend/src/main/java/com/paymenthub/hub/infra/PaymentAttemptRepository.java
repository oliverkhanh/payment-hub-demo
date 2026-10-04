package com.paymenthub.hub.infra;

import com.paymenthub.hub.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findTopByPaymentIdOrderByAttemptNoDesc(Long paymentId);

    Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);
}
