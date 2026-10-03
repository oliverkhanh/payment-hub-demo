package com.paymenthub.hub.infra.security;

import com.paymenthub.hub.api.ProblemWriter;
import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import com.paymenthub.hub.domain.Merchant;
import com.paymenthub.hub.infra.MerchantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Xác thực merchant ở CỬA (Increment 3). Một filter, áp cho MỌI endpoint /v1/**.
 *
 * Thứ tự bên trong (docs/specs/02-payment-init-design.md §0.3):
 *   D  cache raw body           — BẮT BUỘC trước mọi người đọc body
 *   F  tra merchant theo api_key + kiểm ACTIVE
 *   C  kiểm cửa sổ thời gian    — rẻ (so 2 số nguyên), làm TRƯỚC
 *   B  verify HMAC              — đắt (SHA-256 + HMAC), làm SAU
 *   -> đặt danh tính vào SecurityContextHolder
 *
 * Vì sao ở filter mà không ở service:
 *   ① vào service thì raw body đã bị Jackson ăn mất;
 *   ② đặt ở đây là MẶC ĐỊNH cho mọi endpoint — không phải việc "phải nhớ" gọi;
 *   ③ đúng tầng: "anh là ai" là hạ tầng, "tạo payment thế nào" là domain;
 *   ④ từ chối trước khi mở transaction.
 */
@Component
public class MerchantHmacAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MerchantHmacAuthenticationFilter.class);

    public static final String HEADER_API_KEY   = "X-Merchant-Key";
    public static final String HEADER_SIGNATURE = "X-Signature";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private final MerchantRepository merchants;
    private final HmacSigner signer;
    private final ProblemWriter problemWriter;
    private final Duration clockSkew;

    public MerchantHmacAuthenticationFilter(MerchantRepository merchants,
                                           HmacSigner signer,
                                           ProblemWriter problemWriter,
                                           @Value("${hub.hmac.clock-skew-seconds:300}") long skewSeconds) {
        this.merchants = merchants;
        this.signer = signer;
        this.problemWriter = problemWriter;
        this.clockSkew = Duration.ofSeconds(skewSeconds);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // D — bọc request TRƯỚC, vì verify cần raw body mà controller cũng cần đọc lại.
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);

        try {
            Merchant merchant = authenticate(cached);

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new MerchantAuthentication(merchant));
            SecurityContextHolder.setContext(context);

            chain.doFilter(cached, response);
        } catch (BusinessException e) {
            // Log ghi RÕ nguyên nhân; response thì KHÔNG (không tiết lộ key nào có thật).
            log.warn("Từ chối request {} {}: {} — {}", request.getMethod(),
                    request.getRequestURI(), e.errorCode().code(), e.getMessage());
            SecurityContextHolder.clearContext();
            problemWriter.write(response, request.getRequestURI(), e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Merchant authenticate(CachedBodyHttpServletRequest request) {
        String apiKey = request.getHeader(HEADER_API_KEY);
        String signature = request.getHeader(HEADER_SIGNATURE);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);

        if (isBlank(apiKey) || isBlank(signature) || isBlank(timestamp)) {
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE, "Thiếu header xác thực.");
        }

        // F — tra merchant. 401 (KHÔNG 404): trả 404 là tiết lộ api_key nào tồn tại.
        Merchant merchant = merchants.findByApiKey(apiKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SIGNATURE,
                        "api_key không tồn tại: " + apiKey));

        if (!merchant.isActive()) {
            // 403, KHÁC 401: "biết anh là ai, nhưng anh không được phép" -> retry vô nghĩa.
            throw new BusinessException(ErrorCode.MERCHANT_SUSPENDED);
        }

        // C — cửa sổ thời gian: chống replay. Rẻ, nên làm trước HMAC.
        requireFreshTimestamp(timestamp);

        // B — verify HMAC trên chuỗi ký chuẩn tắc.
        String canonical = signer.canonicalString(
                request.getMethod(),
                request.getRequestURI(),
                timestamp,
                request.getHeader(HEADER_IDEMPOTENCY_KEY),
                request.getBody());

        boolean ok = merchant.secretsToTry().stream()          // xoay khoá: thử cả secret cũ
                .anyMatch(secret -> signer.matches(signer.sign(secret, canonical), signature));

        if (!ok) {
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE,
                    "signature không khớp cho merchant " + merchant.getMerchantRef());
        }
        return merchant;
    }

    private void requireFreshTimestamp(String raw) {
        final long epochSeconds;
        try {
            epochSeconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE,
                    "X-Timestamp không phải số: " + raw);
        }
        Duration drift = Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now()).abs();
        if (drift.compareTo(clockSkew) > 0) {
            throw new BusinessException(ErrorCode.REQUEST_EXPIRED,
                    "X-Timestamp lệch " + drift.toSeconds() + "s, cho phép " + clockSkew.toSeconds() + "s.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
