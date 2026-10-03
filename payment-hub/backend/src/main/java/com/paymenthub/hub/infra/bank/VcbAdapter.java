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
 * ADAPTER cho Vietcombank.
 *
 * Nét riêng của VCB:
 *   - endpoint:  POST {base}/banks/VCB/v1/transactions
 *   - tham số:   JSON snake_case, "amount" theo MINOR UNIT (trùng đơn vị của Hub)
 *   - khoá idempotency: ở HEADER `Idempotency-Key`  ⇒ dòng ④ của chuỗi ký CÓ giá trị
 *   - mã lỗi:    "result_code" — "00" = OK, "51" = không đủ tiền
 */
@Component
public class VcbAdapter implements BankConnector {

    private static final Logger log = LoggerFactory.getLogger(VcbAdapter.class);

    private final BankHttpClient http;
    private final HubBankProperties banks;
    private final ObjectMapper json;

    public VcbAdapter(BankHttpClient http, HubBankProperties banks, ObjectMapper json) {
        this.http = http;
        this.banks = banks;
        this.json = json;
    }

    @Override public String bankCode()   { return "VCB"; }
    @Override public String displayName(){ return "Vietcombank"; }

    @Override
    public BankTransactionResult createTransaction(BankTransactionRequest request) {
        String url = banks.require(bankCode()).baseUrl() + "/banks/VCB/v1/transactions";

        // Tham số RIÊNG của VCB — đây chính là thứ adapter tồn tại để dịch.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", request.amount());
        payload.put("currency", request.currency());
        payload.put("merchant_name", request.merchantName());
        payload.put("callback_url", request.callbackUrl());
        payload.put("return_url", request.returnUrl());
        payload.put("expires_at", request.expiresAt().toString());

        byte[] body = BankPayloads.toJsonBytes(json, payload);

        log.info("[VCB] POST {} — payment_ref={}, amount={} {}, Idempotency-Key={}",
                url, request.paymentRef(), request.amount(), request.currency(),
                request.idempotencyKey());

        // VCB nhận khoá qua HEADER ⇒ truyền vào đây để nó vừa được gửi vừa được KÝ.
        var response = http.post(bankCode(), url, MediaType.APPLICATION_JSON,
                body, request.idempotencyKey());

        return translate(response);
    }

    /**
     * ⭐ TRÁI TIM CỦA ADAPTER: dịch bảng mã lỗi của VCB sang 4 giá trị Hub hiểu.
     * Lõi nghiệp vụ không bao giờ thấy chuỗi "51" hay "00".
     */
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
            // Bank trả về thứ không đọc được ⇒ KHÔNG BIẾT, không phải từ chối.
            return BankTransactionResult.indeterminate("unparseable_response", response.status());
        }

        String resultCode = root.path("result_code").asText(null);

        if (response.ok() && "00".equals(resultCode)) {
            return BankTransactionResult.accepted(root.path("bank_txn_id").asText(null),
                    root.path("redirect_url").asText(null), response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        return switch (resultCode == null ? "" : resultCode) {
            case "51", "57", "62" -> BankTransactionResult.declined("vcb_" + resultCode, response.status());
            case "99"             -> BankTransactionResult.indeterminate("vcb_99", response.status());
            // ⭐ DÒNG QUAN TRỌNG NHẤT FILE NÀY: mã lỗi LẠ -> INDETERMINATE, không DECLINED.
            // Đoán "thất bại" khi Bank đã trừ tiền là con đường dẫn tới khách mất tiền.
            default -> response.status() != null && response.status() < 500 && response.status() >= 400
                    ? BankTransactionResult.badRequest("vcb_http_" + response.status(), response.status())
                    : BankTransactionResult.indeterminate("vcb_unknown_" + resultCode, response.status());
        };
    }
}
