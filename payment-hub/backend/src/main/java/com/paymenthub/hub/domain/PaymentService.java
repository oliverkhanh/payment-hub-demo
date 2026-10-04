package com.paymenthub.hub.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.api.dto.CreatePaymentRequest;
import com.paymenthub.hub.api.dto.PaymentResponse;
import com.paymenthub.hub.infra.PaymentRepository;
import com.paymenthub.hub.infra.security.HmacSigner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentService {
    public static final String ENDPOINT_CREATE = "POST /v1/payments";

    private final PaymentRepository payments;
    private final PaymentCreator creator;
    private final IdempotencyStore idempotency;
    private final HmacSigner signer;
    private final ObjectMapper json;

    @Value("${hub.checkout.base-url:http://localhost:8080}")
    private final String checkoutBaseUrl;

    public sealed interface Result {
        record Created(PaymentResponse body) implements Result { }
        record Replayed(int status, String rawBody) implements Result { }
    }

    public Result createPayment(Merchant merchant, String idempotencyKey,
                                CreatePaymentRequest req, byte[] rawBody) {
        String fingerprint = signer.sha256Hex(rawBody);

        final Long recordId;
        try {
            recordId = idempotency.claim(merchant.getId(), idempotencyKey,
                    ENDPOINT_CREATE, fingerprint).getId();
        } catch (DataIntegrityViolationException alreadyClaimed) {
            return handleDuplicateKey(merchant, idempotencyKey, fingerprint);
        }
        try {
            validateReturnUrl(merchant, req);
            requireNoActivePayment(merchant, req.orderCode());

            String metadataJson = req.metadata() != null ? req.metadata().toString() : null;
            Payment payment = creator.create(merchant, req, metadataJson);

            var body = PaymentResponse.of(payment, checkoutBaseUrl, req.metadata());

            idempotency.complete(recordId, HttpStatus.CREATED.value(), writeJson(body), payment.getId());
            return new Result.Created(body);
        } catch (PaymentCreator.ActivePaymentExistsException race) {
            idempotency.release(recordId);
            throw conflictFor(merchant, req.orderCode());
        } catch (RuntimeException failure) {
            idempotency.release(recordId);
            throw failure;
        }
    }

    private Result handleDuplicateKey(Merchant merchant, String key, String fingerprint) {
        var existing = idempotency.find(merchant.getId(), key)

                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS));

        if (!existing.matchesFingerprint(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }
        if (existing.isInProgress()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
        }
        return new Result.Replayed(existing.getResponseStatus(), existing.getResponseBody());
    }

    private void requireNoActivePayment(Merchant merchant, String orderCode) {
        List<Payment> active = payments.findByOrder(
                merchant.getId(), orderCode, PaymentCreator.ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw conflictFor(merchant, orderCode);
        }
        boolean alreadyPaid = payments.findByOrder(
                        merchant.getId(), orderCode, List.of(PaymentStatus.CAPTURED))
                .stream().findAny().isPresent();
        if (alreadyPaid) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_PAID,
                    "Đơn " + orderCode + " đã được thanh toán.");
        }
    }

    private BusinessException conflictFor(Merchant merchant, String orderCode) {
        var existing = payments.findByOrder(merchant.getId(), orderCode, PaymentCreator.ACTIVE_STATUSES)
                .stream().findFirst();
        var error = new BusinessException(ErrorCode.PAYMENT_ALREADY_EXISTS,
                "Đơn " + orderCode + " đã có payment đang xử lý.");
        existing.ifPresent(p -> error
                .with("existing_payment_ref", p.getPaymentRef())
                .with("existing_status", p.getStatus().name()));
        return error;
    }

    private void validateReturnUrl(Merchant merchant, CreatePaymentRequest req) {
        if (!merchant.allowsReturnUrl(req.returnUrl())) {
            throw new BusinessException(ErrorCode.INVALID_RETURN_URL,
                    "return_url không thuộc domain đã đăng ký của merchant.");
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getForMerchant(Merchant merchant, String paymentRef) {
        Payment payment = payments.findByPaymentRefAndMerchantId(paymentRef, merchant.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Không tìm thấy payment " + paymentRef + "."));
        return PaymentResponse.of(payment, checkoutBaseUrl, readJson(payment.getMetadata()));
    }

    @Transactional
    public Payment loadForCheckout(String checkoutToken) {
        Payment payment = payments.findByCheckoutToken(checkoutToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Link thanh toán không tồn tại."));
        if (payment.getStatus() == PaymentStatus.CREATED && payment.isCheckoutExpired(Instant.now())) {
            payment.transitionTo(PaymentStatus.EXPIRED);
        }
        return payment;
    }

    @Transactional
    public Payment cancelByCustomer(String checkoutToken) {
        Payment payment = payments.findByCheckoutToken(checkoutToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Link thanh toán không tồn tại."));

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            return payment;
        }
        if (!payment.getStatus().canTransitionTo(PaymentStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_EXISTS,
                    "Payment đang ở trạng thái " + payment.getStatus() + ", không hủy được.");
        }
        payment.transitionTo(PaymentStatus.CANCELLED);
        return payment;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được response", e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String raw) {
        if (raw == null) return null;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
