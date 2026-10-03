package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADAPTER cho Techcombank — CỐ Ý khác VCB ở cả bốn mặt để adapter có việc thật:
 *   - endpoint:  POST {base}/tcb-gateway/payment/init     (đường dẫn khác hẳn)
 *   - tham số:   camelCase, "txnAmount" tính theo ĐỒNG (không phải minor unit)
 *   - khoá idempotency: nằm trong BODY ("requestId"), KHÔNG có header
 *                       ⇒ dòng ④ của chuỗi ký là RỖNG. Chuỗi ký là contract RIÊNG
 *                         của từng đối tác, không phải một công thức toàn cầu.
 *   - mã lỗi:    "status": "SUCCESS" / "code": "INSUFFICIENT_BALANCE"
 */
@Component
public class TcbAdapter implements BankConnector {

    private static final Logger log = LoggerFactory.getLogger(TcbAdapter.class);

    private final BankHttpClient http;
    private final HubBankProperties banks;
    private final ObjectMapper json;

    public TcbAdapter(BankHttpClient http, HubBankProperties banks, ObjectMapper json) {
        this.http = http;
        this.banks = banks;
        this.json = json;
    }

    @Override public String bankCode()    { return "TCB"; }
    @Override public String displayName() { return "Techcombank"; }

    @Override
    public BankTransactionResult createTransaction(BankTransactionRequest request) {
        String url = banks.require(bankCode()).baseUrl() + "/tcb-gateway/payment/init";

        Map<String, Object> payload = new LinkedHashMap<>();
        // ⚠️ TCB nhận số tiền theo ĐỒNG, Hub lưu theo minor unit ⇒ adapter phải QUY ĐỔI.
        // Đúng loại việc mà nếu không có adapter thì sẽ rò rỉ vào lõi nghiệp vụ.
        // Chia ở BIÊN, không bao giờ lưu kiểu thập phân (CLAUDE.md #4).
        payload.put("txnAmount", new java.math.BigDecimal(request.amount())
                .movePointLeft(2).toPlainString());
        payload.put("ccy", request.currency());
        payload.put("merchantName", request.merchantName());
        payload.put("requestId", request.idempotencyKey());     // khoá nằm trong BODY
        payload.put("notifyUrl", request.callbackUrl());
        payload.put("redirectUrl", request.returnUrl());
        payload.put("expiryTime", request.expiresAt().getEpochSecond());

        byte[] body = BankPayloads.toJsonBytes(json, payload);

        log.info("[TCB] POST {} — payment_ref={}, txnAmount={} {}, requestId trong BODY",
                url, request.paymentRef(), payload.get("txnAmount"), request.currency());

        // null ⇒ KHÔNG gửi header Idempotency-Key, và dòng ④ chuỗi ký để rỗng.
        // Tính idempotent vẫn được bảo đảm: Bank dedup theo requestId trong body.
        var response = http.post(bankCode(), url, MediaType.APPLICATION_JSON, body, null);

        return translate(response);
    }

    private BankTransactionResult translate(BankHttpClient.Response response) {
        // ⭐ "Chưa gửi được" KHÁC "gửi rồi mà không biết" — hai nhánh, không gộp.
        if (response.notSent()) {
            return BankTransactionResult.notSent("not_sent:" + response.failureReason());
        }
        if (response.noAnswer()) {
            return BankTransactionResult.indeterminate("no_answer:" + response.failureReason(), null);
        }
        JsonNode root = BankPayloads.parse(json, response.body());
        if (root == null) {
            return BankTransactionResult.indeterminate("unparseable_response", response.status());
        }

        String status = root.path("status").asText("");
        if (response.ok() && "SUCCESS".equals(status)) {
            // Tên field cũng khác VCB: txnRef / paymentUrl.
            return BankTransactionResult.accepted(root.path("txnRef").asText(null),
                    root.path("paymentUrl").asText(null), response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        String code = root.path("code").asText("");
        return switch (code) {
            case "INSUFFICIENT_BALANCE", "ACCOUNT_LOCKED", "LIMIT_EXCEEDED"
                 -> BankTransactionResult.declined("tcb_" + code, response.status());
            case "SYSTEM_BUSY", "TIMEOUT"
                 -> BankTransactionResult.indeterminate("tcb_" + code, response.status());
            case "INVALID_REQUEST", "SIGNATURE_INVALID"
                 -> BankTransactionResult.badRequest("tcb_" + code, response.status());
            // Mã lạ -> KHÔNG BIẾT. Không bao giờ default sang DECLINED.
            default -> BankTransactionResult.indeterminate("tcb_unknown_" + code, response.status());
        };
    }
}
