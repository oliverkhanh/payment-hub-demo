package com.paymenthub.bank.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.bank.domain.TransactionService;
import com.paymenthub.bank.infra.security.BankCredentialProperties;
import com.paymenthub.bank.infra.security.BankHmacVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DIALECT VCB — POST /banks/{bankCode}/v1/transactions
 *
 * JSON snake_case · khoá idempotency ở HEADER `Idempotency-Key` · mã lỗi "result_code".
 *
 * Đây là biên CROSS-ORG: BANK ra luật, Hub phải ký bằng credential Bank cấp.
 *
 * ⭐ Nhận `byte[] raw` chứ KHÔNG nhận DTO: chữ ký phải được kiểm trên đúng byte đã truyền
 * qua dây. Parse JSON chỉ xảy ra SAU KHI chữ ký hợp lệ — code chưa xác thực thì không
 * được chạm vào dữ liệu chưa xác thực.
 */
@RestController
public class BankTransactionController {

    private static final Logger log = LoggerFactory.getLogger(BankTransactionController.class);

    private final TransactionService transactions;
    private final HubCallAuth auth;
    private final BankCredentialProperties banks;
    private final BankRedirects redirects;
    private final ObjectMapper json;

    public BankTransactionController(TransactionService transactions, HubCallAuth auth,
                                     BankCredentialProperties banks, BankRedirects redirects,
                                     ObjectMapper json) {
        this.transactions = transactions;
        this.auth = auth;
        this.banks = banks;
        this.redirects = redirects;
        this.json = json;
    }

    @PostMapping(value = "/banks/{bankCode}/v1/transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@PathVariable String bankCode,
                                    @RequestHeader(value = BankHmacVerifier.HEADER_IDEMPOTENCY,
                                            required = false) String idempotencyKey,
                                    @RequestBody byte[] raw,
                                    HttpServletRequest http) {

        if (!banks.supports(bankCode)) {
            return fail(HttpStatus.NOT_FOUND, "404", "Ngân hàng không tồn tại.");
        }

        // ⚠️ Verify TRƯỚC khi parse, parse TRƯỚC khi chạm vào nghiệp vụ.
        if (!auth.authentic(bankCode, http, raw, idempotencyKey)) {
            return fail(HttpStatus.UNAUTHORIZED, "401", "Xác thực thất bại.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "400", "Thiếu header Idempotency-Key.");
        }

        JsonNode body;
        try {
            body = json.readTree(raw);
        } catch (Exception notJson) {
            return fail(HttpStatus.BAD_REQUEST, "400", "Body không phải JSON hợp lệ.");
        }

        long amount = body.path("amount").asLong(0);
        if (amount <= 0) {
            // "51" trong bảng mã của VCB là nhóm từ chối; sai tham số là nhóm 4xx.
            return fail(HttpStatus.UNPROCESSABLE_ENTITY, "4001", "amount phải > 0.");
        }

        var result = transactions.createOrGet(new TransactionService.CreateCommand(
                bankCode, amount,
                body.path("currency").asText("VND"),
                body.path("merchant_name").asText(""),
                idempotencyKey,
                body.path("callback_url").asText(""),
                body.path("return_url").asText("")));

        var txn = result.transaction();
        log.info("[{}] mở phiên {} cho khoá {} (amount={} minor unit) — {}",
                bankCode, txn.getBankTxnId(), idempotencyKey, amount,
                result.created() ? "TẠO MỚI" : "TRẢ LẠI BẢN CŨ (idempotent)");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result_code", "00");
        payload.put("bank_txn_id", txn.getBankTxnId());
        // Hub coi URL này là OPAQUE — dùng nguyên xi, không tự ghép.
        payload.put("redirect_url", redirects.checkoutUrl(bankCode, txn.getBankTxnId()));

        // Retry cùng khoá -> 200 thay vì 201, để Hub phân biệt được (giống Inc 3).
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(payload);
    }

    private ResponseEntity<Map<String, Object>> fail(HttpStatus status, String resultCode,
                                                     String message) {
        return ResponseEntity.status(status).body(Map.of(
                "result_code", resultCode, "message", message));
    }
}
