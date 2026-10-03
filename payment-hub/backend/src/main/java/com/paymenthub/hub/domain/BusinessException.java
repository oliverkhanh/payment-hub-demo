package com.paymenthub.hub.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lỗi nghiệp vụ có mã ổn định. ApiExceptionHandler dịch sang application/problem+json. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultDetail());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    /** Thêm field ngoài RFC 7807, ví dụ existing_payment_ref cho ca 409. */
    public BusinessException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, Object> extensions() { return extensions; }
}
