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

/**
 * Điều phối việc tạo payment.
 *
 * ⚠️ CỐ Ý KHÔNG @Transactional ở createPayment (bài học Increment 2):
 * khi PaymentCreator.create (tx riêng) rollback vì đụng UNIQUE, PostgreSQL abort tx đó
 * (SQLSTATE 25P02). Nếu createPayment nằm TRONG tx đó thì mọi SELECT sau đây cũng chết
 * -> 500. Để nó ngoài tx thì phần recovery chạy ở tx SẠCH.
 */
@Service
public class PaymentService {

    public static final String ENDPOINT_CREATE = "POST /v1/payments";

    private final PaymentRepository payments;
    private final PaymentCreator creator;
    private final IdempotencyStore idempotency;
    private final HmacSigner signer;
    private final ObjectMapper json;
    private final String checkoutBaseUrl;

    public PaymentService(PaymentRepository payments, PaymentCreator creator,
                          IdempotencyStore idempotency, HmacSigner signer, ObjectMapper json,
                          @Value("${hub.checkout.base-url:http://localhost:8080}") String checkoutBaseUrl) {
        this.payments = payments;
        this.creator = creator;
        this.idempotency = idempotency;
        this.signer = signer;
        this.json = json;
        this.checkoutBaseUrl = checkoutBaseUrl;
    }

    /** Kết quả trả lên controller: 201 (tạo mới) hoặc 200 (replay, body nguyên văn). */
    public sealed interface Result {
        record Created(PaymentResponse body) implements Result { }
        record Replayed(int status, String rawBody) implements Result { }
    }

    public Result createPayment(Merchant merchant, String idempotencyKey,
                                CreatePaymentRequest req, byte[] rawBody) {

        String fingerprint = signer.sha256Hex(rawBody);

        // ── BƯỚC 1: GIÀNH CHỖ. INSERT trước, KHÔNG select trước. ──
        // Hai request đồng thời cùng SELECT đều thấy "chưa có" -> cả hai cùng tạo payment.
        // Chỉ UNIQUE(merchant_id, idempotency_key) ở DB mới phân định được ai thắng.
        //
        // Bắt lỗi Ở ĐÂY (ngoài mọi transaction) chứ không bắt trong IdempotencyStore:
        // bắt bên trong rồi return bình thường thì Spring đi commit một tx mà PostgreSQL
        // đã abort -> 500.
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
            // BƯỚC 3: đóng dấu — lưu NGUYÊN VĂN response. Không dựng lại từ DB về sau,
            // vì 20 phút nữa payment đã EXPIRED -> dựng lại cho ra kết quả KHÁC.
            idempotency.complete(recordId, HttpStatus.CREATED.value(), writeJson(body), payment.getId());
            return new Result.Created(body);

        } catch (PaymentCreator.ActivePaymentExistsException race) {
            // Đơn này vừa có payment sống được tạo bởi request khác (khoá idempotency KHÁC).
            idempotency.release(recordId);
            throw conflictFor(merchant, req.orderCode());

        } catch (RuntimeException failure) {
            // Nghiệp vụ thất bại -> nhả chỗ đã giành, để merchant sửa rồi gửi lại cùng khoá.
            idempotency.release(recordId);
            throw failure;
        }
    }

    /**
     * Khoá đã tồn tại -> ba nhánh:
     *   fingerprint KHÁC      -> 422 (bug/tấn công phía merchant, phải GÀO LÊN chứ không giấu)
     *   state = IN_PROGRESS   -> 409 (có request khác đang bay; không chờ, không chạy song song)
     *   state = COMPLETED     -> phát lại response cũ NGUYÊN VĂN
     */
    private Result handleDuplicateKey(Merchant merchant, String key, String fingerprint) {
        var existing = idempotency.find(merchant.getId(), key)
                // Cực hiếm: thằng thắng rollback ngay sau khi ta đụng UNIQUE. Coi như xung đột.
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS));

        if (!existing.matchesFingerprint(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }
        if (existing.isInProgress()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
        }
        return new Result.Replayed(existing.getResponseStatus(), existing.getResponseBody());
    }

    /** Lớp chống trùng #2 — kiểm TRƯỚC (ca thường). Chốt chặn atomic vẫn là partial index. */
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

    /** Chống open redirect: không tin client, kể cả client đã xác thực HMAC. */
    private void validateReturnUrl(Merchant merchant, CreatePaymentRequest req) {
        if (!merchant.allowsReturnUrl(req.returnUrl())) {
            throw new BusinessException(ErrorCode.INVALID_RETURN_URL,
                    "return_url không thuộc domain đã đăng ký của merchant.");
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getForMerchant(Merchant merchant, String paymentRef) {
        // Tenant isolation: tra theo (payment_ref, merchant_id). Payment của merchant khác
        // trả 404 chứ không 403 — 403 là xác nhận "ref này CÓ tồn tại".
        Payment payment = payments.findByPaymentRefAndMerchantId(paymentRef, merchant.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Không tìm thấy payment " + paymentRef + "."));
        return PaymentResponse.of(payment, checkoutBaseUrl, readJson(payment.getMetadata()));
    }

    /** Ana mở trang checkout. Hết hạn thì đánh dấu luôn, không đợi job. */
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

    /**
     * Ana bấm "quay lại". Idempotent theo TRẠNG THÁI, không cần Idempotency-Key:
     * bấm lần hai thấy đã CANCELLED -> trả về chính nó, không lỗi.
     */
    @Transactional
    public Payment cancelByCustomer(String checkoutToken) {
        Payment payment = payments.findByCheckoutToken(checkoutToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Link thanh toán không tồn tại."));

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            return payment;                                   // double-click -> vô hại
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
