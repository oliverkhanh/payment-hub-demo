package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.bank.dto.AcbPaySessionRequest;
import com.paymenthub.hub.infra.bank.dto.AcbPaySessionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AcbAdapter implements BankConnector {

    private static final String BANK_CODE = "ACB";
    private static final String PATH = "/acb/api/v2/pay-session";

    private final BankHttpClient bankHttpClient;
    private final HubBankProperties hubBankProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String bankCode() {
        return BANK_CODE;
    }

    @Override
    public BankTransactionResult createTransaction(BankTransactionRequest request) {
        String url = hubBankProperties.require(BANK_CODE).baseUrl() + PATH;
        byte[] body = BankPayloads.toFormBytes(objectMapper, formOf(request));

        log.info("[ACB] POST {} (form-urlencoded) payment_ref={} REF_NO={}",
                url, request.paymentRef(), request.idempotencyKey());

        var response = bankHttpClient.post(BANK_CODE, url,
                MediaType.APPLICATION_FORM_URLENCODED, body, null);

        return translate(response);
    }

    private AcbPaySessionRequest formOf(BankTransactionRequest request) {
        return new AcbPaySessionRequest(
                String.valueOf(request.amount()),
                request.currency(),
                request.merchantName(),
                request.idempotencyKey(),
                request.callbackUrl(),
                request.returnUrl(),
                String.valueOf(request.expiresAt().getEpochSecond()));
    }

    private BankTransactionResult translate(BankHttpClient.Response response) {
        if (response.notSent()) {
            return BankTransactionResult.notSent("not_sent:" + response.failureReason());
        }
        if (response.noAnswer()) {
            return BankTransactionResult.indeterminate("no_answer:" + response.failureReason(), null);
        }

        AcbPaySessionResponse body =
                BankPayloads.read(objectMapper, response.body(), AcbPaySessionResponse.class);
        if (body == null) {
            return BankTransactionResult.indeterminate("unparseable_response", response.status());
        }

        int errorNo = body.errorNoOrMissing();

        if (response.ok() && errorNo == 0) {
            return BankTransactionResult.accepted(body.txnId(), body.payUrl(), response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        return switch (errorNo) {
            case 9527, 9528, 9530 -> BankTransactionResult.declined("acb_" + errorNo, response.status());
            case 9999 -> BankTransactionResult.indeterminate("acb_9999", response.status());
            case 4001, 4003 -> BankTransactionResult.badRequest("acb_" + errorNo, response.status());
            default -> BankTransactionResult.indeterminate("acb_unknown_" + errorNo, response.status());
        };
    }
}
