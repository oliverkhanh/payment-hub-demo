package com.paymenthub.hub.domain;

import java.util.Set;

/**
 * State machine của Payment (docs/specs/02-payment-init-design.md §0.2).
 *
 * Inc 3 chạm: CREATED, EXPIRED, CANCELLED.
 * Inc 4 thêm: IN_PROGRESS, CAPTURED, FAILED.
 * AUTHORIZED / VOIDED / REFUNDED: giữ trong enum nhưng CHƯA dùng (Inc 6 mở refund).
 *
 * Trade-off: production tách AUTHORIZED (hold) khỏi CAPTURED (thu) vì luật card network
 * cấm capture trước khi giao hàng. Demo gộp auth+capture (mô hình "sale", như hàng số
 * giao ngay) vì không có kho và không có vận chuyển.
 */
public enum PaymentStatus {

    /** Đã tạo lệnh, đang chờ Ana mở link và nhập thông tin thanh toán. */
    CREATED,

    /**
     * Đã gửi lệnh sang Bank, CHƯA có kết luận. Bao gồm cả ca timeout ("khả nghi").
     *
     * Đi ra được CREATED — cạnh LÙI duy nhất của state machine này. Chỉ dùng khi
     * BankOutcome.NOT_SENT, tức biết chắc request chưa hề tới bank: lúc đó IN_PROGRESS
     * chưa bao giờ ĐÚNG, ta chỉ đặt chỗ hụt. Xem Payment.releaseForRetry().
     */
    IN_PROGRESS,

    /** Tiền đã chuyển. Terminal. */
    CAPTURED,

    /** Bank TRẢ LỜI từ chối. Terminal — thử lại = payment MỚI. */
    FAILED,

    /** Hết hạn link checkout (job của Hub quét). Terminal. */
    EXPIRED,

    /** Ana chủ động bỏ. Terminal. */
    CANCELLED,

    // ── chưa dùng ở Inc 3/4 ──
    AUTHORIZED,
    VOIDED,
    REFUNDED;

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case CREATED     -> Set.of(IN_PROGRESS, EXPIRED, CANCELLED);
            case IN_PROGRESS -> Set.of(CAPTURED, FAILED, CREATED);
            default          -> Set.of();     // terminal
        };
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /**
     * "Đang sống" = còn có thể dẫn tới việc thu tiền.
     * Phải TRÙNG KHỚP với điều kiện của partial index uq_payment_active trong V2__payments.sql.
     */
    public boolean isActive() {
        return this == CREATED || this == IN_PROGRESS;
    }
}
