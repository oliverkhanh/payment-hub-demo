package com.paymenthub.bank.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DIALECT TCB — POST /tcb-gateway/payment/init
 *
 * Cố ý KHÁC VCB ở bốn điểm, để adapter phía Hub có việc thật:
 *   - đường dẫn không có {bankCode}: bank code nằm trong chính endpoint
 *   - camelCase, "txnAmount" theo ĐỒNG (không phải minor unit)
 *   - khoá idempotency trong BODY ("requestId"), KHÔNG có header
 *     ⇒ dòng ④ chuỗi ký là RỖNG. Chuỗi ký là contract riêng của từng đối tác.
 *   - mã lỗi: "status"/"code" dạng chuỗi
 */
@RestController
public class TcbGatewayController {

    private static final Logger log = LoggerFactory.getLogger(TcbGatewayController.class);
    private static final String BANK_CODE = "TCB";

    private final TransactionService transactions;
    private final HubCallAuth auth;
    private final BankCredentialProperties banks;
    private final BankRedirects redirects;
    private final ObjectMapper json;

    public TcbGatewayController(TransactionService transactions, HubCallAuth auth,
                                BankCredentialProperties banks, BankRedirects redirects,
                                ObjectMapper json) {
        this.transactions = transactions;
        this.auth = auth;
        this.banks = banks;
        this.redirects = redirects;
        this.json = json;
    }

    @PostMapping(value = "/tcb-gateway/payment/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> init(@RequestBody byte[] raw, HttpServletRequest http) {

        if (!banks.supports(BANK_CODE)) {
            return fail(HttpStatus.NOT_FOUND, "CONFIG_MISSING", "Bank chưa được cấu hình.");
        }

        // null ở tham số thứ 4: TCB KHÔNG dùng header Idempotency-Key, nên dòng ④ của
        // chuỗi ký phải rỗng — đúng như phía Hub đã ký.
        if (!auth.authentic(BANK_CODE, http, raw, null)) {
            return fail(HttpStatus.UNAUTHORIZED, "SIGNATURE_INVALID", "Xác thực thất bại.");
        }

        JsonNode body;
        try {
            body = json.readTree(raw);
        } catch (Exception notJson) {
            return fail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Body không phải JSON.");
        }

        String requestId = body.path("requestId").asText(null);
        if (requestId == null || requestId.isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Thiếu requestId.");
        }

        // ⚠️ TCB nhận ĐỒNG, nội bộ bank lưu minor unit ⇒ nhân 100 ở BIÊN.
        // BigDecimal, không phải double: 150000.07 * 100 với double ra 15000006.999...
        BigDecimal txnAmount = new BigDecimal(body.path("txnAmount").asText("0"));
        long minorUnit = txnAmount.movePointRight(2).longValueExact();
        if (minorUnit <= 0) {
            return fail(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "txnAmount phải > 0.");
        }

        var result = transactions.createOrGet(new TransactionService.CreateCommand(
                BANK_CODE, minorUnit,
                body.path("ccy").asText("VND"),
                body.path("merchantName").asText(""),
                requestId,
                body.path("notifyUrl").asText(""),
                body.path("redirectUrl").asText("")));

        var txn = result.transaction();
        log.info("[TCB] mở phiên {} cho requestId {} (txnAmount={} -> {} minor unit) — {}",
                txn.getBankTxnId(), requestId, txnAmount, minorUnit,
                result.created() ? "TẠO MỚI" : "TRẢ LẠI BẢN CŨ (idempotent)");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "SUCCESS");
        payload.put("txnRef", txn.getBankTxnId());          // tên field khác VCB
        payload.put("paymentUrl", redirects.checkoutUrl(BANK_CODE, txn.getBankTxnId()));

        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(payload);
    }

    private ResponseEntity<Map<String, Object>> fail(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(Map.of(
                "status", "FAILED", "code", code, "message", msg));
    }
}
