package com.paymenthub.hub.domain.bank;

/**
 * PORT — cửa ra ngân hàng. Nằm ở tầng domain nên KHÔNG biết gì về HTTP, JSON hay
 * mã lỗi riêng của bank nào.
 *
 * Mỗi ngân hàng có một ADAPTER hiện thực interface này (xem infra.bank). Việc của
 * adapter là DỊCH:
 *   - từ ngôn ngữ chung của Hub  →  URL + tham số riêng của bank đó
 *   - từ mã lỗi riêng của bank   →  BankOutcome chung của Hub
 *
 * CỐ Ý không `sealed`: đây là interface kiểu "làm được việc gì" — thêm bank thứ 4
 * phải là THÊM MỘT BEAN, không sửa dòng nào ở chỗ khác.
 *
 * Phép thử thiết kế: mai thay VCB giả bằng VCB thật ⇒ chỉ sửa MỘT adapter + config.
 * Phải sửa BankPaymentService / state machine / callback handler là đã rò rỉ chi tiết.
 */
public interface BankConnector {

    /** Mã định tuyến — khớp với key trong cấu hình `hub.banks`. */
    String bankCode();

    String displayName();

    /** Tạo giao dịch ở bank và lấy URL để chuyển hướng khách sang đó. */
    BankTransactionResult createTransaction(BankTransactionRequest request);
}
