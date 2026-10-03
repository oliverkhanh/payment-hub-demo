package com.paymenthub.hub.api;

import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** Dịch exception -> application/problem+json. Chỉ áp cho REST API, không cho trang checkout. */
@RestControllerAdvice(basePackages = "com.paymenthub.hub.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemWriter problems;

    public ApiExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String, Object>> onBusiness(BusinessException e, HttpServletRequest req) {
        return problem(e.errorCode(), e.getMessage(), req, e.extensions());
    }

    /**
     * @Valid thất bại -> 422 (đọc hiểu được request, chỉ là giá trị sai luật).
     * Trả DANH SÁCH lỗi thay vì từng cái một, để merchant không phải sửa qua 5 vòng.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException e,
                                                     HttpServletRequest req) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "detail", String.valueOf(fe.getDefaultMessage())))
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED,
                errors.size() + " field không hợp lệ.", req, Map.of("errors", errors));
    }

    /** Thiếu header bắt buộc -> 400 (thiếu thành phần GIAO THỨC, không phải giá trị sai). */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> onMissingHeader(MissingRequestHeaderException e,
                                                        HttpServletRequest req) {
        ErrorCode code = "Idempotency-Key".equalsIgnoreCase(e.getHeaderName())
                ? ErrorCode.IDEMPOTENCY_KEY_REQUIRED
                : ErrorCode.MALFORMED_REQUEST;
        return problem(code, "Thiếu header " + e.getHeaderName() + ".", req, Map.of());
    }

    /** JSON hỏng -> 400 (không đọc hiểu nổi). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> onUnreadable(HttpMessageNotReadableException e,
                                                     HttpServletRequest req) {
        return problem(ErrorCode.MALFORMED_REQUEST, "Body không phải JSON hợp lệ.", req, Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest req) {
        log.error("Lỗi không lường trước ở {} {}", req.getMethod(), req.getRequestURI(), e);
        Map<String, Object> body = problems.body(ErrorCode.MALFORMED_REQUEST,
                "Lỗi hệ thống.", req.getRequestURI(), Map.of());
        body.put("status", 500);
        body.put("error_code", "internal_error");
        body.put("title", "Internal error");
        return ResponseEntity.status(500).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private ResponseEntity<Map<String, Object>> problem(ErrorCode code, String detail,
                                                        HttpServletRequest req,
                                                        Map<String, Object> extensions) {
        var response = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (code == ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS) {
            response = response.header("Retry-After", "1");
        }
        return response.body(problems.body(code, detail, req.getRequestURI(), extensions));
    }
}
