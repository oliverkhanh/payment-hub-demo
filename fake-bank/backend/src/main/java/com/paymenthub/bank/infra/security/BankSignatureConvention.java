package com.paymenthub.bank.infra.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public enum BankSignatureConvention {

    VCB("/banks/VCB/v1/transactions", IdempotencyKeySource.HEADER),
    TCB("/tcb-gateway/payment/init", IdempotencyKeySource.BODY),
    ACB("/acb/api/v2/pay-session", IdempotencyKeySource.BODY),
    SCB("/scb/openapi/v1/payment-orders", IdempotencyKeySource.BODY),
    BIDV("/bidv/openapi/v1/orders", IdempotencyKeySource.BODY);

    public enum IdempotencyKeySource { HEADER, BODY }

    public static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private final String createTransactionPath;
    private final IdempotencyKeySource idempotencyKeySource;

    BankSignatureConvention(String createTransactionPath,
                            IdempotencyKeySource idempotencyKeySource) {
        this.createTransactionPath = createTransactionPath;
        this.idempotencyKeySource = idempotencyKeySource;
    }

    public String bankCode() {
        return name();
    }

    public String createTransactionPath() {
        return createTransactionPath;
    }

    public String canonicalIdempotencyLine(HttpServletRequest request) {
        if (idempotencyKeySource == IdempotencyKeySource.BODY) {
            return "";
        }
        return request.getHeader(HEADER_IDEMPOTENCY);
    }

    public Map<String, Object> invalidSignatureBody() {
        return switch (this) {
            case VCB -> orderedMap("result_code", "4003", "message", "Invalid signature");
            case TCB -> orderedMap("status", "FAILED", "code", "SIGNATURE_INVALID",
                                   "message", "Invalid signature");
            case ACB -> orderedMap("error_no", 4003, "error_desc", "INVALID SIGNATURE");
            case SCB -> Map.of("Header", orderedMap("ResponseCode", "401",
                                                    "ResponseDesc", "Invalid signature"));
            case BIDV -> orderedMap("rc", "401", "rd", "Invalid signature");
        };
    }

    public static Optional<BankSignatureConvention> forPath(String path) {
        return Arrays.stream(values())
                .filter(convention -> convention.createTransactionPath.equals(path))
                .findFirst();
    }

    private static Map<String, Object> orderedMap(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
