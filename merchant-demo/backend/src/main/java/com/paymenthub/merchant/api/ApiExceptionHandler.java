package com.paymenthub.merchant.api;

import com.paymenthub.merchant.domain.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Dịch lỗi ra application/problem+json (RFC 7807), kèm mã lỗi nội bộ ổn định (error_code).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setTitle("Business rule violated");
        pd.setProperty("error_code", ex.getErrorCode());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống không mong đợi.");
        pd.setTitle("Internal error");
        pd.setProperty("error_code", "internal_error");
        return pd;
    }
}
