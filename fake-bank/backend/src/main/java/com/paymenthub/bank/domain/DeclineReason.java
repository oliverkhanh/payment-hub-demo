package com.paymenthub.bank.domain;

/**
 * Lý do bank từ chối. Lưu vào bank_transactions.decline_reason để ĐỐI SOÁT và điều tra.
 *
 * ⭐ Phân biệt hai tầng, đây là điểm cốt lõi của file này:
 *   - `code()`    → ghi vào DB và log. CHÍNH XÁC, đủ chi tiết để người vận hành điều tra.
 *   - `message()` → hiện cho khách. CỐ Ý MƠ HỒ ở nhóm xác thực.
 *
 * ACCOUNT_NOT_FOUND và INVALID_PIN có message GIỐNG HỆT NHAU. Nếu tách ra thì kẻ tấn công
 * gõ bừa mã tài khoản là biết được mã nào CÓ THẬT — tức là ta vừa biếu không nửa đầu
 * của bài toán dò mật khẩu.
 */
public enum DeclineReason {

    ACCOUNT_NOT_FOUND("account_not_found", "Mã tài khoản hoặc mã PIN không đúng."),

    // ⬆⬇ Hai dòng này PHẢI giữ cùng một message. Đừng "cải thiện trải nghiệm" bằng cách
    //    nói rõ cái nào sai — đó là một lỗ bảo mật, không phải một tính năng.
    INVALID_PIN("invalid_pin", "Mã tài khoản hoặc mã PIN không đúng."),

    /**
     * Chỉ lộ ra SAU KHI PIN đã đúng. Nói "tài khoản bị khoá" cho người gõ sai PIN
     * cũng là xác nhận tài khoản đó tồn tại.
     */
    ACCOUNT_LOCKED("account_locked", "Tài khoản đang bị khoá. Vui lòng liên hệ ngân hàng."),

    INSUFFICIENT_FUNDS("insufficient_funds",
            "Số dư không đủ. Bạn có thể dùng tài khoản khác."),

    /** Ana chủ động bỏ cuộc — khác hẳn các lý do trên: đây là lựa chọn, không phải lỗi. */
    CANCELLED_BY_USER("cancelled_by_user", "Bạn đã hủy giao dịch này.");

    private final String code;
    private final String message;

    DeclineReason(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** Dùng cho DB + log. */
    public String code() { return code; }

    /** Dùng cho màn hình của khách. */
    public String message() { return message; }
}
