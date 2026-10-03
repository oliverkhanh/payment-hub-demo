package com.paymenthub.hub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dựng body lỗi application/problem+json (RFC 7807).
 *
 * Field CHUẨN:  type (URI định danh LOẠI lỗi) · title (mô tả loại) · status ·
 *               detail (mô tả LẦN NÀY) · instance (URI lần xảy ra).
 * Field MỞ RỘNG: error_code (hợp đồng ổn định cho máy đọc) · các extension khác.
 *
 * Quy ước: chỉ `error_code` + `status` là hợp đồng. `detail` là cho NGƯỜI đọc,
 * được phép đổi tự do, merchant không nên parse nó.
 *
 * Dùng cả ở filter (chưa vào Spring MVC nên @ControllerAdvice không bắt được).
 */
@Component
public class ProblemWriter {

    private final ObjectMapper objectMapper;

    public ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> body(ErrorCode code, String detail, String instance,
                                    Map<String, Object> extensions) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", code.typeUri());
        problem.put("title", code.title());
        problem.put("status", code.status().value());
        problem.put("detail", detail != null ? detail : code.defaultDetail());
        problem.put("instance", instance);
        problem.put("error_code", code.code());
        if (extensions != null) problem.putAll(extensions);
        return problem;
    }

    public void write(HttpServletResponse response, String instance, BusinessException e)
            throws IOException {
        ErrorCode code = e.errorCode();
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (code == ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS) {
            response.setHeader("Retry-After", "1");
        }
        objectMapper.writeValue(response.getOutputStream(),
                body(code, publicDetail(e), instance, e.extensions()));
    }

    /**
     * Lỗi xác thực: KHÔNG kể chi tiết ra ngoài (kể = giúp kẻ tấn công dò).
     * Chi tiết đã được log ở filter.
     */
    private String publicDetail(BusinessException e) {
        return switch (e.errorCode()) {
            case INVALID_SIGNATURE, REQUEST_EXPIRED -> e.errorCode().defaultDetail();
            default -> e.getMessage();
        };
    }
}
