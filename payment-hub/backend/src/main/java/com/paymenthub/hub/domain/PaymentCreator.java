package com.paymenthub.hub.domain;

import com.paymenthub.hub.api.dto.CreatePaymentRequest;
import com.paymenthub.hub.infra.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Ghi payment vào DB. BEAN RIÊNG có chủ đích (bài học Increment 2):
 *   - @Transactional chỉ hiệu lực khi gọi từ BEAN KHÁC (qua proxy);
 *   - khi partial index uq_payment_active nổ, transaction NÀY rollback gọn, còn
 *     PaymentService (không @Transactional) vẫn ở tx sạch để xử lý tiếp.
 *
 * Inc 5 sẽ thêm ghi bảng outbox vào ĐÚNG transaction này — lúc đó tính nguyên tử của
 * "payment + event" mới thực sự cần tới @Transactional ở đây.
 */
@Service
public class PaymentCreator {

    private final PaymentRepository payments;
    private final PaymentRefGenerator refs;

    public PaymentCreator(PaymentRepository payments, PaymentRefGenerator refs) {
        this.payments = payments;
        this.refs = refs;
    }

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

        // saveAndFlush: ép INSERT chạm DB NGAY, để vi phạm partial index nổ TRONG tx này
        // và bắt được tại chỗ (save() có thể hoãn INSERT tới commit -> nổ ngoài try/catch).
        try {
            return payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException conflict) {
            // Dịch lỗi DB thành lỗi nghiệp vụ có nghĩa. Ở đây tx đã abort, KHÔNG query tiếp
            // được (PostgreSQL 25P02) -> ném ra để PaymentService đọc lại ở tx sạch.
            throw new ActivePaymentExistsException(conflict);
        }
    }

    /** Partial index uq_payment_active nổ = đơn này đang có payment sống. */
    public static class ActivePaymentExistsException extends RuntimeException {
        public ActivePaymentExistsException(Throwable cause) { super(cause); }
    }

    public static final List<PaymentStatus> ACTIVE_STATUSES =
            List.of(PaymentStatus.CREATED, PaymentStatus.IN_PROGRESS);
}
