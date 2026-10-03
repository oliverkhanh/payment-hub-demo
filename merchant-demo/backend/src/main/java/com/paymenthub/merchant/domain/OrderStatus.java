package com.paymenthub.merchant.domain;

import java.util.Set;

/**
 * State machine của Order phía merchant (Increment 2).
 * Phase 1 chỉ tạo ở CREATED; các chuyển tiếp còn lại dùng ở Phase 2/3 (gọi Hub, webhook).
 * Nguyên tắc (CLAUDE.md #3): chuyển trạng thái phải hợp lệ, không set tùy tiện.
 */
public enum OrderStatus {
    CREATED,           // vừa tạo, chờ khởi tạo thanh toán
    AWAITING_PAYMENT,  // đã gọi Hub, chờ kết quả (async)
    PAID,              // Hub báo trả thành công
    FAILED,            // khởi tạo lỗi HOẶC Hub báo trả thất bại
    EXPIRED,           // hết hạn / khách bỏ ngang
    CANCELLED;         // khách hủy chủ động

    private Set<OrderStatus> next() {
        return switch (this) {
            case CREATED          -> Set.of(AWAITING_PAYMENT, FAILED, CANCELLED);
            case AWAITING_PAYMENT -> Set.of(PAID, FAILED, EXPIRED, CANCELLED);
            default               -> Set.of(); // PAID/FAILED/EXPIRED/CANCELLED là terminal
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return next().contains(target);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }
}
