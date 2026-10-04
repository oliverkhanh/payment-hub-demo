package com.paymenthub.hub.infra.security;

import com.paymenthub.hub.api.ProblemWriter;
import com.paymenthub.hub.config.BankCallbackProperties;
import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class BankCallbackHmacAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_BANK_CODE = "X-Bank-Code";
    public static final String HEADER_SIGNATURE = "X-Bank-Signature";
    public static final String HEADER_TIMESTAMP = "X-Bank-Timestamp";
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private final BankCallbackProperties bankCallbackProperties;
    private final HmacSigner hmacSigner;
    private final ProblemWriter problemWriter;
    private final Duration clockSkew;

    public BankCallbackHmacAuthenticationFilter(
            BankCallbackProperties bankCallbackProperties,
            HmacSigner hmacSigner,
            ProblemWriter problemWriter,
            @Value("${hub.hmac.clock-skew-seconds:300}") long skewSeconds) {
        this.bankCallbackProperties = bankCallbackProperties;
        this.hmacSigner = hmacSigner;
        this.problemWriter = problemWriter;
        this.clockSkew = Duration.ofSeconds(skewSeconds);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);

        try {
            String bankCode = authenticate(cached);

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new BankCallbackAuthentication(bankCode));
            SecurityContextHolder.setContext(context);

            chain.doFilter(cached, response);
        } catch (BusinessException rejected) {
            log.warn("Từ chối bank callback {} {}: {} — {}", request.getMethod(),
                    request.getRequestURI(), rejected.errorCode().code(), rejected.getMessage());
            SecurityContextHolder.clearContext();
            problemWriter.write(response, request.getRequestURI(), rejected);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String authenticate(CachedBodyHttpServletRequest request) {
        String bankCode = request.getHeader(HEADER_BANK_CODE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);

        if (isBlank(bankCode) || isBlank(signature) || isBlank(timestamp)) {
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE, "Thiếu header xác thực.");
        }

        String webhookSecret = bankCallbackProperties.secretFor(bankCode);
        if (webhookSecret == null) {
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE,
                    "Chưa cấu hình hub.bank-callback.secrets." + bankCode);
        }

        requireFreshTimestamp(timestamp);

        String canonical = hmacSigner.canonicalString(
                request.getMethod(),
                request.getRequestURI(),
                timestamp,
                request.getHeader(HEADER_IDEMPOTENCY_KEY),
                request.getBody());

        if (!hmacSigner.matches(hmacSigner.sign(webhookSecret, canonical), signature)) {
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE,
                    "signature không khớp cho bank " + bankCode);
        }
        return bankCode;
    }

    private void requireFreshTimestamp(String raw) {
        final long epochSeconds;
        try {
            epochSeconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE,
                    HEADER_TIMESTAMP + " không phải số: " + raw);
        }

        Duration drift = Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now()).abs();
        if (drift.compareTo(clockSkew) > 0) {
            throw new BusinessException(ErrorCode.REQUEST_EXPIRED,
                    HEADER_TIMESTAMP + " lệch " + drift.toSeconds() + "s, cho phép "
                            + clockSkew.toSeconds() + "s.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
