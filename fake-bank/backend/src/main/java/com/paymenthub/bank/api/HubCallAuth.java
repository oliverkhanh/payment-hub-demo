package com.paymenthub.bank.api;

import com.paymenthub.bank.infra.security.BankHmacVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cổng xác thực DÙNG CHUNG cho ba dialect API. Ba ngân hàng khác nhau ở định dạng
 * tham số, nhưng giống nhau ở GIAO THỨC KÝ — nên chỗ ký chỉ được có một bản.
 *
 * ⭐ `raw` phải là BYTE THÔ đọc từ body, KHÔNG phải DTO serialize lại.
 * Serialize lại là nguồn gốc của lỗi "chữ ký đúng mà vẫn 401": Hub ký trên byte nó gửi,
 * còn Bank ký trên byte Jackson dựng lại — thêm một dấu cách, đổi thứ tự khoá, mất
 * field null là ra chuỗi khác ngay. Xem docs/notes/hmac-request-signing.md §5.
 */
@Component
public class HubCallAuth {

    private static final Logger log = LoggerFactory.getLogger(HubCallAuth.class);

    private final BankHmacVerifier verifier;

    public HubCallAuth(BankHmacVerifier verifier) {
        this.verifier = verifier;
    }

    /**
     * @param signedIdempotencyKey giá trị dòng ④ của chuỗi ký — tức ĐÚNG giá trị header
     *        `Idempotency-Key` mà Hub gửi, hoặc null nếu contract của bank này không có
     *        header đó (TCB/ACB đặt khoá trong body). Truyền sai chỗ này là 401 ngay.
     */
    public boolean authentic(String bankCode, HttpServletRequest http, byte[] raw,
                             String signedIdempotencyKey) {
        boolean ok = verifier.verify(bankCode, http.getMethod(), http.getRequestURI(),
                http.getHeader(BankHmacVerifier.HEADER_KEY),
                http.getHeader(BankHmacVerifier.HEADER_TIMESTAMP),
                signedIdempotencyKey, raw,
                http.getHeader(BankHmacVerifier.HEADER_SIGNATURE));
        if (!ok) {
            log.warn("[{}] từ chối request của Hub tới {}: chữ ký/timestamp không hợp lệ",
                    bankCode, http.getRequestURI());
        }
        return ok;
    }
}
