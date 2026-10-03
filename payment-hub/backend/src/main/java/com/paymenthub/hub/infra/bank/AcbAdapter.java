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
 * ADAPTER cho ACB — kiểu hệ thống cũ, khác hẳn hai bank kia:
 *   - endpoint:  POST {base}/acb/api/v2/pay-session
 *   - tham số:   FORM-URLENCODED (không phải JSON), tên viết HOA_GẠCH_DƯỚI
 *                ⇒ adapter đổi luôn cả ĐỊNH DẠNG, không chỉ tên field.
 *   - khoá idempotency: "REF_NO" trong form, không có header ⇒ dòng ④ chuỗi ký RỖNG
 *   - mã lỗi:    "error_no": 0 = OK, 9527 = không đủ tiền  (SỐ, không phải chuỗi)
 */
@Component
public class AcbAdapter implements BankConnector {

    private static final Logger log = LoggerFactory.getLogger(AcbAdapter.class);

    private final BankHttpClient http;
    private final HubBankProperties banks;
    private final ObjectMapper json;

    public AcbAdapter(BankHttpClient http, HubBankProperties banks, ObjectMapper json) {
        this.http = http;
        this.banks = banks;
        this.json = json;
    }

    @Override public String bankCode()    { return "ACB"; }
    @Override public String displayName() { return "ACB"; }

    @Override
    public BankTransactionResult createTransaction(BankTransactionRequest request) {
        String url = banks.require(bankCode()).baseUrl() + "/acb/api/v2/pay-session";

        Map<String, String> form = new LinkedHashMap<>();
        form.put("AMT", String.valueOf(request.amount()));
        form.put("CUR_CODE", request.currency());
        form.put("MER_NAME", request.merchantName());
        form.put("REF_NO", request.idempotencyKey());
        form.put("NOTIFY_URL", request.callbackUrl());
        form.put("RET_URL", request.returnUrl());
        form.put("EXP_TS", String.valueOf(request.expiresAt().getEpochSecond()));

        byte[] body = BankPayloads.toFormBytes(form);

        log.info("[ACB] POST {} (form-urlencoded) — payment_ref={}, REF_NO={}",
                url, request.paymentRef(), request.idempotencyKey());

        // Chữ ký vẫn là SHA-256 trên ĐÚNG byte gửi đi — form hay JSON không quan trọng,
        // vì công thức ký làm việc trên byte, không trên cấu trúc dữ liệu.
        var response = http.post(bankCode(), url, MediaType.APPLICATION_FORM_URLENCODED,
                body, null);

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

        // ACB trả mã lỗi dạng SỐ. Dùng -1 làm "không có field" để phân biệt với 0 = OK.
        int errorNo = root.path("error_no").asInt(-1);

        if (response.ok() && errorNo == 0) {
            return BankTransactionResult.accepted(root.path("txn_id").asText(null),
                    root.path("pay_url").asText(null), response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        return switch (errorNo) {
            case 9527, 9528, 9530 -> BankTransactionResult.declined("acb_" + errorNo, response.status());
            case 9999             -> BankTransactionResult.indeterminate("acb_9999", response.status());
            case 4001, 4003       -> BankTransactionResult.badRequest("acb_" + errorNo, response.status());
            default               -> BankTransactionResult.indeterminate("acb_unknown_" + errorNo, response.status());
        };
    }
}
