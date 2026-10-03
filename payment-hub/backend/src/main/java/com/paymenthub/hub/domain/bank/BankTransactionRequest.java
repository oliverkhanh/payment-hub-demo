package com.paymenthub.hub.domain.bank;

import java.time.Instant;

/**
 * Yêu cầu Hub gửi sang bank để TẠO một giao dịch — viết bằng ngôn ngữ CHUNG của Hub.
 * Adapter của từng bank sẽ dịch nó sang tham số riêng của bank đó.
 *
 * Để ý: KHÔNG có mã tài khoản, KHÔNG có PIN. Hub chỉ biết khách trả QUA ĐÂU,
 * không biết trả BẰNG GÌ (spec §2.6 — né phạm vi PCI DSS).
 */
public record BankTransactionRequest(
        String paymentRef,
        long amount,                  // minor unit (CLAUDE.md #4)
        String currency,
        String merchantName,
        /** Khoá idempotency Hub gửi sang Bank. Gửi lại phải GIỮ NGUYÊN khoá này. */
        String idempotencyKey,
        /** Kênh A: bank → Hub (server→server, ký HMAC). */
        String callbackUrl,
        /** Kênh B: bank → trình duyệt Ana. */
        String returnUrl,
        /** Hạn giao dịch, để giao dịch bên Bank không sống lâu hơn link checkout bên Hub. */
        Instant expiresAt
) { }
