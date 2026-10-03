package com.paymenthub.hub.domain.bank;

/**
 * NGÔN NGỮ CHUNG của Hub về câu trả lời của ngân hàng.
 *
 * Hub chỉ biết 4 giá trị này. Nó KHÔNG biết "51" của VCB hay "9527" của ACB nghĩa là gì —
 * việc dịch là của adapter. Nhờ vậy thêm bank mới không phải sửa lõi nghiệp vụ.
 *
 * Enum này được lưu thẳng vào payment_attempts.outcome (NULL = chưa có trả lời).
 */
public enum BankOutcome {

    /**
     * Bank đã TẠO giao dịch và trả về redirect_url.
     *
     * Cố ý KHÔNG đặt tên "SUCCESS": ở bước này khách chưa trả đồng nào, mới chỉ có
     * một giao dịch chờ ở bank. Gọi là SUCCESS là mời người đọc sau hiểu sai.
     */
    ACCEPTED,

    /** Bank từ chối DỨT KHOÁT (hết tiền, khoá tài khoản, sai PIN). Gửi lại vô nghĩa. */
    DECLINED,

    /**
     * ⭐ BIẾT CHẮC request CHƯA HỀ RA KHỎI MÁY HUB — bank chết, DNS sai, connect timeout,
     * (sau này) circuit breaker đang mở.
     *
     * Khác INDETERMINATE ở đúng một điểm, và điểm đó quyết định mọi thứ:
     *   NOT_SENT       -> ta BIẾT bank không nhận gì  ⇒ tiền chắc chắn còn nguyên
     *   INDETERMINATE  -> ta KHÔNG BIẾT bank nhận gì  ⇒ tiền có thể đã bị trừ
     * Vì vậy NOT_SENT được phép mở payment ra cho thử lại ngay, còn INDETERMINATE thì không.
     */
    NOT_SENT,

    /**
     * ⭐ KHÔNG BIẾT — timeout, 5xx, lỗi mạng, hoặc mã lỗi LẠ chưa từng gặp.
     *
     * Đây là chỗ gánh bài học trung tâm của Increment 4: mọi thứ không phải câu trả lời
     * dứt khoát đều phải về đây. TUYỆT ĐỐI không lẳng lặng coi là DECLINED — đoán
     * "thất bại" khi Bank đã trừ tiền là con đường dẫn tới khách trả hai lần.
     */
    INDETERMINATE,

    /** Request của CHÍNH TA sai (400/422). Lỗi lập trình, gửi lại vô nghĩa. */
    BAD_REQUEST;

    /**
     * Request chưa hề tới bank ⇒ payment chưa thực sự "đang xử lý" ⇒ mở ra cho thử lại.
     * Đây là nhánh DUY NHẤT được phép đưa status lùi về CREATED.
     */
    public boolean neverReachedBank() {
        return this == NOT_SENT;
    }

    /**
     * Bank ĐÃ TRẢ LỜI và trả lời là không ⇒ được phép đóng payment thành FAILED.
     * INDETERMINATE không đủ điều kiện — ta không biết gì.
     * NOT_SENT cũng không dùng nhánh này: nó đi nhánh thử lại ở trên.
     */
    public boolean rulesOutCharge() {
        return this == DECLINED || this == BAD_REQUEST;
    }
}
