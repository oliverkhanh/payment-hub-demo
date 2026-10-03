package com.paymenthub.hub.domain.bank;

/**
 * Câu trả lời của bank, đã được adapter DỊCH về ngôn ngữ chung của Hub.
 *
 * `redirectUrl` là OPAQUE — Hub dùng nguyên xi, không phân tích, không tự ghép lại.
 * (Cùng nguyên tắc với checkout_url mà Hub trả cho merchant ở Inc 3.)
 *
 * `httpStatus` và `errorCode` chỉ để GHI LOG / ĐỐI SOÁT. Lõi nghiệp vụ quyết định dựa
 * trên `outcome` — nếu nó phải đọc errorCode để quyết thì adapter đã dịch chưa xong việc.
 */
public record BankTransactionResult(String bankTxnId, String redirectUrl, BankOutcome outcome,
                                    Integer httpStatus, String errorCode) {

    /** Bank đã tạo giao dịch và cho URL để chuyển khách sang. */
    public static BankTransactionResult accepted(String bankTxnId, String redirectUrl,
                                                 Integer httpStatus) {
        return new BankTransactionResult(bankTxnId, redirectUrl, BankOutcome.ACCEPTED,
                httpStatus, null);
    }

    /** Bank trả lời DỨT KHOÁT là không. Tiền chắc chắn không bị trừ. */
    public static BankTransactionResult declined(String errorCode, Integer httpStatus) {
        return new BankTransactionResult(null, null, BankOutcome.DECLINED, httpStatus, errorCode);
    }

    /**
     * ⭐ Request CHƯA HỀ ra khỏi máy Hub — biết chắc bank không nhận gì.
     * httpStatus = null vì không có cú HTTP nào hoàn thành.
     */
    public static BankTransactionResult notSent(String errorCode) {
        return new BankTransactionResult(null, null, BankOutcome.NOT_SENT, null, errorCode);
    }

    /**
     * ⭐ ĐÃ GỬI nhưng KHÔNG BIẾT KẾT QUẢ — read timeout, 5xx, mã lỗi lạ.
     * Đây là nhánh phải chọn khi còn nghi ngờ, KHÔNG được chọn declined().
     */
    public static BankTransactionResult indeterminate(String errorCode, Integer httpStatus) {
        return new BankTransactionResult(null, null, BankOutcome.INDETERMINATE, httpStatus, errorCode);
    }

    /** Request của CHÍNH Hub sai. Lỗi lập trình, gửi lại vô nghĩa. */
    public static BankTransactionResult badRequest(String errorCode, Integer httpStatus) {
        return new BankTransactionResult(null, null, BankOutcome.BAD_REQUEST, httpStatus, errorCode);
    }

    public boolean isAccepted() {
        return outcome == BankOutcome.ACCEPTED && redirectUrl != null;
    }
}
