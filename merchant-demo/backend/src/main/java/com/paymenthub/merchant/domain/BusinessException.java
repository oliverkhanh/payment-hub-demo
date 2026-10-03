package com.paymenthub.merchant.domain;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ có mã ổn định (error_code) + HTTP status.
 * Được ApiExceptionHandler dịch ra application/problem+json (RFC 7807).
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public BusinessException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
