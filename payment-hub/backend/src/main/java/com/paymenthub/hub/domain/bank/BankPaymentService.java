package com.paymenthub.hub.domain.bank;

import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.Payment;
import com.paymenthub.hub.domain.PaymentAttempt;
import com.paymenthub.hub.domain.PaymentRefGenerator;
import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.infra.PaymentAttemptRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BankPaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentRefGenerator paymentRefGenerator;
    private final HubBankProperties hubBankProperties;

    @Transactional
    public BankCallDecision startBankPayment(String checkoutToken, String bankCode) {
        if (!hubBankProperties.isEnabled(bankCode)) {
            log.warn("Từ chối bank_code không hợp lệ: {}", bankCode);
            return stop(StopReason.BANK_NOT_AVAILABLE);
        }

        Payment payment = paymentRepository.findByCheckoutToken(checkoutToken).orElse(null);
        if (payment == null) {
            return stop(StopReason.PAYMENT_NOT_FOUND);
        }

        Instant now = Instant.now();
        if (payment.isCheckoutExpired(now)) {
            return stop(StopReason.CHECKOUT_EXPIRED);
        }
        if (payment.getStatus() != PaymentStatus.CREATED) {
            return stop(reasonFor(payment.getStatus()));
        }
        if (!tryClaim(payment.getId(), now)) {
            return stop(reasonForLostRace(payment.getId()));
        }

        PaymentAttempt attempt = openAttempt(payment, bankCode);
        return new BankCallDecision.Proceed(payment.getId(), attempt.getId(), bankCode,
                attempt.getIdempotencyKey(), snapshotOf(payment));
    }

    @Transactional
    public void applyBankResult(Long paymentId, Long attemptId, BankTransactionResult result) {
        Instant now = Instant.now();
        settlePayment(paymentId, result.outcome(), now);
        recordAttemptOutcome(attemptId, result, now);
    }

    private void settlePayment(Long paymentId, BankOutcome outcome, Instant now) {
        if (outcome.neverReachedBank()) {
            warnIfClaimGone(paymentId, outcome, paymentRepository.releaseClaim(paymentId, now));
            return;
        }
        if (outcome.rulesOutCharge()) {
            warnIfClaimGone(paymentId, outcome, paymentRepository.markFailed(paymentId, now));
        }
    }

    private void warnIfClaimGone(Long paymentId, BankOutcome outcome, int rowsChanged) {
        if (rowsChanged == 0) {
            log.warn("Payment {} không còn ở IN_PROGRESS khi ghi kết quả {} — giữ trạng thái hiện tại",
                    paymentId, outcome);
        }
    }

    private void recordAttemptOutcome(Long attemptId, BankTransactionResult result, Instant now) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId).orElse(null);
        if (attempt == null) {
            log.error("Không tìm thấy attempt {} để ghi kết quả {}", attemptId, result.outcome());
            return;
        }
        attempt.recordResult(result, now);
        paymentAttemptRepository.save(attempt);

        log.info("Attempt #{} của payment {} đóng với outcome={} code={} bank_txn_id={}",
                attempt.getAttemptNo(), attempt.getPaymentId(),
                result.outcome(), result.errorCode(), result.bankTxnId());
    }

    private boolean tryClaim(Long paymentId, Instant now) {
        return paymentRepository.markInProgress(paymentId, now) == 1;
    }

    private PaymentAttempt openAttempt(Payment payment, String bankCode) {
        PaymentAttempt attempt = paymentAttemptRepository.save(new PaymentAttempt(
                payment.getId(), nextAttemptNo(payment.getId()), bankCode,
                paymentRefGenerator.newBankIdempotencyKey()));

        log.info("Mở attempt #{} cho payment {} qua {} (khoá {})",
                attempt.getAttemptNo(), payment.getPaymentRef(),
                attempt.getBankCode(), attempt.getIdempotencyKey());

        return attempt;
    }

    private int nextAttemptNo(Long paymentId) {
        return paymentAttemptRepository.findTopByPaymentIdOrderByAttemptNoDesc(paymentId)
                .map(attempt -> attempt.getAttemptNo() + 1)
                .orElse(1);
    }

    private static PaymentSnapshot snapshotOf(Payment payment) {
        return new PaymentSnapshot(
                payment.getPaymentRef(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getMerchant().getName(),
                payment.getCheckoutExpiresAt());
    }

    private StopReason reasonForLostRace(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .map(Payment::getStatus)
                .map(BankPaymentService::reasonFor)
                .orElse(StopReason.PAYMENT_NOT_FOUND);
    }

    private static StopReason reasonFor(PaymentStatus status) {
        return switch (status) {
            case IN_PROGRESS -> StopReason.ALREADY_IN_PROGRESS;
            case CAPTURED    -> StopReason.ALREADY_PAID;
            case CANCELLED   -> StopReason.CANCELLED;
            case EXPIRED     -> StopReason.CHECKOUT_EXPIRED;
            case FAILED      -> StopReason.EARLIER_ATTEMPT_FAILED;
            default          -> StopReason.NO_LONGER_ACTIVE;
        };
    }

    private static BankCallDecision.Stop stop(StopReason reason) {
        return new BankCallDecision.Stop(reason);
    }
}
