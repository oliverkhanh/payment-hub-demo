package com.paymenthub.hub.infra;

import com.paymenthub.hub.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    /** Lần gọi gần nhất của một payment — dùng để tái sử dụng phiên đã mở. */
    Optional<PaymentAttempt> findTopByPaymentIdOrderByAttemptNoDesc(Long paymentId);

    /** Đối soát: đi tìm theo khoá đã gửi sang Bank (thành phần sau). */
    Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);
}
