package com.paymenthub.hub.domain;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_SIGNATURE("invalid_signature", HttpStatus.UNAUTHORIZED,
            "Invalid signature", "Xác thực request thất bại."),
    REQUEST_EXPIRED("request_expired", HttpStatus.UNAUTHORIZED,
            "Request expired", "X-Timestamp nằm ngoài cửa sổ cho phép."),

    MERCHANT_SUSPENDED("merchant_suspended", HttpStatus.FORBIDDEN,
            "Merchant suspended", "Merchant đang bị tạm ngưng."),

    RATE_LIMIT_EXCEEDED("rate_limit_exceeded", HttpStatus.TOO_MANY_REQUESTS,
            "Rate limit exceeded", "Vượt quá hạn mức request."),

    IDEMPOTENCY_KEY_REQUIRED("idempotency_key_required", HttpStatus.BAD_REQUEST,
            "Idempotency-Key required", "Thiếu header Idempotency-Key."),
    MALFORMED_REQUEST("malformed_request", HttpStatus.BAD_REQUEST,
            "Malformed request", "Không đọc được nội dung request."),

    VALIDATION_FAILED("validation_failed", HttpStatus.UNPROCESSABLE_ENTITY,
            "Validation failed", "Dữ liệu không hợp lệ."),
    INVALID_RETURN_URL("invalid_return_url", HttpStatus.UNPROCESSABLE_ENTITY,
            "Invalid return_url", "return_url không thuộc danh sách domain đã đăng ký."),
    UNSUPPORTED_BANK("unsupported_bank", HttpStatus.UNPROCESSABLE_ENTITY,
            "Unsupported bank", "Ngân hàng không được hỗ trợ."),
    IDEMPOTENCY_KEY_REUSE("idempotency_key_reuse", HttpStatus.UNPROCESSABLE_ENTITY,
            "Idempotency key reuse", "Idempotency-Key đã dùng cho một request khác nội dung."),

    IDEMPOTENCY_KEY_IN_PROGRESS("idempotency_key_in_progress", HttpStatus.CONFLICT,
            "Request in progress", "Request đang được xử lý, vui lòng thử lại."),
    PAYMENT_ALREADY_EXISTS("payment_already_exists", HttpStatus.CONFLICT,
            "Payment already exists", "Đơn hàng này đã có payment đang xử lý."),
    ORDER_ALREADY_PAID("order_already_paid", HttpStatus.CONFLICT,
            "Order already paid", "Đơn hàng này đã được thanh toán."),

    PAYMENT_NOT_FOUND("payment_not_found", HttpStatus.NOT_FOUND,
            "Payment not found", "Không tìm thấy payment.");

    private final String code;
    private final HttpStatus status;
    private final String title;
    private final String defaultDetail;

    ErrorCode(String code, HttpStatus status, String title, String defaultDetail) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.defaultDetail = defaultDetail;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public String title() { return title; }
    public String defaultDetail() { return defaultDetail; }

    public String typeUri() {
        return "https://paymenthub.dev/errors/" + code.replace('_', '-');
    }
}
