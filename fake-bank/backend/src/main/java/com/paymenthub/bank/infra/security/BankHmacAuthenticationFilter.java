package com.paymenthub.bank.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.bank.config.BankProperties;
import com.paymenthub.bank.config.BankProperties.ApiClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@RequiredArgsConstructor
public class BankHmacAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_KEY = "X-Hub-Key";
    public static final String HEADER_SIGNATURE = "X-Hub-Signature";
    public static final String HEADER_TIMESTAMP = "X-Hub-Timestamp";

    private final BankProperties bankProperties;
    private final HmacSigner hmacSigner;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        BankSignatureConvention convention =
                BankSignatureConvention.forPath(request.getRequestURI()).orElse(null);
        if (convention == null) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        String rejection = rejectionReason(cached, convention);

        if (rejection != null) {
            log.warn("[{}] TỪ CHỐI {} {}: {}", convention.bankCode(),
                    request.getMethod(), request.getRequestURI(), rejection);
            writeInvalidSignature(response, convention);
            return;
        }

        chain.doFilter(cached, response);
    }

    private String rejectionReason(CachedBodyHttpServletRequest request,
                                   BankSignatureConvention convention) {
        String apiKey = request.getHeader(HEADER_KEY);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String signature = request.getHeader(HEADER_SIGNATURE);

        if (isBlank(apiKey) || isBlank(timestamp) || isBlank(signature)) {
            return "thiếu header xác thực";
        }

        ApiClient apiClient = bankProperties.apiClient(convention.bankCode());
        if (apiClient == null) {
            return "chưa cấu hình bank.api-clients." + convention.bankCode();
        }
        if (!apiClient.key().equals(apiKey)) {
            return "api key không khớp: " + apiKey;
        }

        String staleTimestamp = timestampProblem(timestamp);
        if (staleTimestamp != null) {
            return staleTimestamp;
        }

        String canonical = hmacSigner.canonicalString(
                request.getMethod(),
                request.getRequestURI(),
                timestamp,
                convention.canonicalIdempotencyLine(request),
                request.getBody());

        if (!hmacSigner.matches(hmacSigner.sign(apiClient.secret(), canonical), signature)) {
            return "signature không khớp";
        }
        return null;
    }

    private String timestampProblem(String raw) {
        final long epochSeconds;
        try {
            epochSeconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            return "X-Hub-Timestamp không phải số: " + raw;
        }

        Duration clockSkew = bankProperties.hmacClockSkew();
        Duration drift = Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now()).abs();

        if (drift.compareTo(clockSkew) > 0) {
            return "X-Hub-Timestamp lệch " + drift.toSeconds()
                    + "s, cho phép " + clockSkew.toSeconds() + "s";
        }
        return null;
    }

    private void writeInvalidSignature(HttpServletResponse response,
                                       BankSignatureConvention convention) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), convention.invalidSignatureBody());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
