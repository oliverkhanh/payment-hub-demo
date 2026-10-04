package com.paymenthub.hub.domain;

import com.paymenthub.hub.api.dto.CreatePaymentRequest;
import com.paymenthub.hub.infra.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentCreator {
    private final PaymentRepository payments;
    private final PaymentRefGenerator refs;

    @Transactional
    public Payment create(Merchant merchant, CreatePaymentRequest req, String metadataJson) {
        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(req.expiresInSecondsOrDefault()));

        Payment payment = new Payment(
                merchant,
                req.orderCode(),
                req.amount(),
                req.currency(),
                req.returnUrl(),
                req.description(),
                metadataJson,
                refs.newPaymentRef(),
                refs.newCheckoutToken(),
                expiresAt);

        try {
            return payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException conflict) {
            throw new ActivePaymentExistsException(conflict);
        }
    }

    public static class ActivePaymentExistsException extends RuntimeException {
        public ActivePaymentExistsException(Throwable cause) { super(cause); }
    }

    public static final List<PaymentStatus> ACTIVE_STATUSES =
            List.of(PaymentStatus.CREATED, PaymentStatus.IN_PROGRESS);
}
