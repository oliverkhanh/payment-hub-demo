package com.paymenthub.bank.api;

import com.paymenthub.bank.domain.TransactionService;
import com.paymenthub.bank.infra.security.BankCredentialProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DIALECT ACB — POST /acb/api/v2/pay-session
 *
 * Kiểu hệ thống cũ:
 *   - FORM-URLENCODED, không phải JSON
 *   - tham số HOA_GẠCH_DƯỚI, khoá idempotency là "REF_NO" trong form (không có header)
 *   - mã lỗi "error_no" dạng SỐ: 0 = OK, 9527 = không đủ tiền
 *
 * ⭐ Vẫn nhận byte[] và tự parse form: nếu khai `@RequestParam` thì Spring đã đọc hết
 * input stream để dựng parameter map, và lúc verify ta KHÔNG CÒN byte thô nào để băm.
 * Chữ ký phải tính trên byte, không trên map đã parse.
 */
@RestController
public class AcbGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AcbGatewayController.class);
    private static final String BANK_CODE = "ACB";

    private final TransactionService transactions;
    private final HubCallAuth auth;
    private final BankCredentialProperties banks;
    private final BankRedirects redirects;

    public AcbGatewayController(TransactionService transactions, HubCallAuth auth,
                                BankCredentialProperties banks, BankRedirects redirects) {
        this.transactions = transactions;
        this.auth = auth;
        this.banks = banks;
        this.redirects = redirects;
    }

    @PostMapping(value = "/acb/api/v2/pay-session",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> paySession(@RequestBody byte[] raw, HttpServletRequest http) {

        if (!banks.supports(BANK_CODE)) {
            return fail(HttpStatus.NOT_FOUND, 4004, "Bank chưa được cấu hình.");
        }
        if (!auth.authentic(BANK_CODE, http, raw, null)) {
            return fail(HttpStatus.UNAUTHORIZED, 4003, "Xác thực thất bại.");
        }

        Map<String, String> form = parseForm(raw);
        String refNo = form.get("REF_NO");
        if (refNo == null || refNo.isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, 4001, "Thiếu REF_NO.");
        }

        long amount;
        try {
            amount = Long.parseLong(form.getOrDefault("AMT", "0"));
        } catch (NumberFormatException e) {
            return fail(HttpStatus.UNPROCESSABLE_ENTITY, 4001, "AMT không hợp lệ.");
        }
        if (amount <= 0) {
            return fail(HttpStatus.UNPROCESSABLE_ENTITY, 4001, "AMT phải > 0.");
        }

        var result = transactions.createOrGet(new TransactionService.CreateCommand(
                BANK_CODE, amount,
                form.getOrDefault("CUR_CODE", "VND"),
                form.getOrDefault("MER_NAME", ""),
                refNo,
                form.getOrDefault("NOTIFY_URL", ""),
                form.getOrDefault("RET_URL", "")));

        var txn = result.transaction();
        log.info("[ACB] mở phiên {} cho REF_NO {} (AMT={}) — {}",
                txn.getBankTxnId(), refNo, amount,
                result.created() ? "TẠO MỚI" : "TRẢ LẠI BẢN CŨ (idempotent)");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error_no", 0);
        payload.put("txn_id", txn.getBankTxnId());
        payload.put("pay_url", redirects.checkoutUrl(BANK_CODE, txn.getBankTxnId()));

        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(payload);
    }

    private static Map<String, String> parseForm(byte[] raw) {
        Map<String, String> form = new HashMap<>();
        String text = new String(raw, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            form.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                     URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return form;
    }

    private ResponseEntity<Map<String, Object>> fail(HttpStatus status, int errorNo, String desc) {
        return ResponseEntity.status(status).body(Map.of(
                "error_no", errorNo, "error_desc", desc));
    }
}
