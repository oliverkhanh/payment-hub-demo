package com.paymenthub.hub.domain;

import com.paymenthub.hub.api.dto.BankCallbackRequest;
import com.paymenthub.hub.infra.PaymentAttemptRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankCallbackService {

    private static final String EVENT_SUCCEEDED = "BankTransaction.Succeeded";
    private static final String EVENT_CANCELLED = "BankTransaction.Cancelled";


    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentRepository paymentRepository;

    @Transactional
    public BankCallbackOutcome apply(String signedBankCode, BankCallbackRequest callback) {
        PaymentAttempt attempt = paymentAttemptRepository
                .findByIdempotencyKey(callback.idempotencyKey())
                .orElse(null);

        if (attempt == null) {
            log.warn("Callback từ {} mang idempotency_key không tồn tại: {}",
                    signedBankCode, callback.idempotencyKey());
            return BankCallbackOutcome.ATTEMPT_NOT_FOUND;
        }

        if (!attempt.getBankCode().equals(signedBankCode)) {
            log.error("Callback ký bởi {} nhưng attempt {} thuộc bank {}",
                    signedBankCode, attempt.getId(), attempt.getBankCode());
            return BankCallbackOutcome.ATTEMPT_NOT_FOUND;
        }

        Payment payment = paymentRepository.findById(attempt.getPaymentId()).orElse(null);
        if (payment == null) {
            log.error("Attempt {} trỏ tới payment {} không tồn tại",
                    attempt.getId(), attempt.getPaymentId());
            return BankCallbackOutcome.ATTEMPT_NOT_FOUND;
        }

        if (!matchesMoney(payment, callback)) {
            log.error("LỆCH TIỀN trên callback {}: hub ghi {} {}, bank báo {} {} — KHÔNG capture",
                    callback.eventId(), payment.getAmount(), payment.getCurrency(),
                    callback.amount(), callback.currency());
            return BankCallbackOutcome.AMOUNT_MISMATCH;
        }

        return switch (callback.eventType()) {
            case EVENT_SUCCEEDED -> capture(payment, callback);
            case EVENT_CANCELLED -> cancel(payment, callback);
            default -> unsupported(callback);
        };
    }

    private BankCallbackOutcome capture(Payment payment, BankCallbackRequest callback) {
        int captured = paymentRepository.markCaptured(payment.getId(), Instant.now());
        if (captured == 1) {
            log.info("Callback {} capture payment {} ({} {})", callback.eventId(),
                    payment.getPaymentRef(), payment.getAmount(), payment.getCurrency());
            return BankCallbackOutcome.CAPTURED;
        }

        return alreadySettled(payment.getId(), PaymentStatus.CAPTURED, callback);
    }

    private BankCallbackOutcome cancel(Payment payment, BankCallbackRequest callback) {
        int cancelled = paymentRepository.markCancelled(payment.getId(), Instant.now());
        if (cancelled == 1) {
            log.info("Callback {} huỷ payment {} theo yêu cầu của khách",
                    callback.eventId(), payment.getPaymentRef());
            return BankCallbackOutcome.CANCELLED;
        }

        return alreadySettled(payment.getId(), PaymentStatus.CANCELLED, callback);
    }

    private BankCallbackOutcome unsupported(BankCallbackRequest callback) {
        log.error("Callback {} mang event_type lạ: {}", callback.eventId(), callback.eventType());
        return BankCallbackOutcome.UNSUPPORTED_EVENT;
    }

    private boolean matchesMoney(Payment payment, BankCallbackRequest callback) {
        return payment.getAmount() == callback.amount()
                && payment.getCurrency().equals(callback.currency());
    }

    private BankCallbackOutcome alreadySettled(Long paymentId, PaymentStatus expected,
                                               BankCallbackRequest callback) {
        PaymentStatus current = paymentRepository.findById(paymentId)
                .map(Payment::getStatus)
                .orElse(null);

        if (current == expected) {
            log.info("Callback {} là bản trùng, payment đã {} từ trước",
                    callback.eventId(), expected);
            return BankCallbackOutcome.ALREADY_SETTLED;
        }

        log.error("Callback {} không chốt được sang {}: payment {} đang ở trạng thái {}",
                callback.eventId(), expected, paymentId, current);
        return BankCallbackOutcome.NOT_SETTLEABLE;
    }
}
