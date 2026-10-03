package com.paymenthub.hub.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Sinh định danh công khai. Mỗi khán giả một định danh (docs/notes/inc3-data-model.md §2).
 *
 * Dùng SecureRandom, KHÔNG dùng số tự tăng: lộ số tự tăng ra ngoài = cho phép
 * enumeration + rò rỉ thông tin kinh doanh (trừ hai id là biết lưu lượng giao dịch/giờ).
 */
@Component
public class PaymentRefGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    /** Cho MERCHANT: sống vĩnh viễn, dùng đối soát/hỗ trợ. */
    public String newPaymentRef() {
        return "pay_" + randomToken(12);          // ~96 bit
    }

    /**
     * Cho ANA: VÉ VÀO CỬA (capability), không chỉ là định danh.
     * Trang checkout không có login ⇒ chuỗi này là thứ duy nhất chứng minh quyền trả đơn
     * ⇒ phải đủ dài để không brute-force được.
     */
    public String newCheckoutToken() {
        return "ctk_" + randomToken(24);          // ~192 bit
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL_SAFE.encodeToString(buffer);
    }
}
