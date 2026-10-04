package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.bank.dto.VcbCreateTransactionRequest;
import com.paymenthub.hub.infra.bank.dto.VcbCreateTransactionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VcbAdapter implements BankConnector {

    private static final String BANK_CODE = "VCB";
    private static final String PATH = "/banks/VCB/v1/transactions";

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
        byte[] body = BankPayloads.toJsonBytes(objectMapper, payloadOf(request));

        log.info("[VCB] POST {} payment_ref={} amount={} {} Idempotency-Key={}",
                url, request.paymentRef(), request.amount(), request.currency(),
                request.idempotencyKey());

        var response = bankHttpClient.post(BANK_CODE, url, MediaType.APPLICATION_JSON,
                body, request.idempotencyKey());

        return translate(response);
    }

    private VcbCreateTransactionRequest payloadOf(BankTransactionRequest request) {
        return new VcbCreateTransactionRequest(
                request.amount(),
                request.currency(),
                request.merchantName(),
                request.callbackUrl(),
                request.returnUrl(),
                request.expiresAt().toString());
    }

    private BankTransactionResult translate(BankHttpClient.Response response) {
        if (response.notSent()) {
            return BankTransactionResult.notSent("not_sent:" + response.failureReason());
        }
        if (response.noAnswer()) {
            return BankTransactionResult.indeterminate("no_answer:" + response.failureReason(), null);
        }

        VcbCreateTransactionResponse body =
                BankPayloads.read(objectMapper, response.body(), VcbCreateTransactionResponse.class);
        if (body == null) {
            return BankTransactionResult.indeterminate("unparseable_response", response.status());
        }

        String resultCode = body.resultCodeOrEmpty();

        if (response.ok() && "00".equals(resultCode)) {
            return BankTransactionResult.accepted(body.bankTxnId(), body.redirectUrl(),
                    response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        return switch (resultCode) {
            case "51", "57", "62" -> BankTransactionResult.declined("vcb_" + resultCode, response.status());
            case "99" -> BankTransactionResult.indeterminate("vcb_99", response.status());
            case "4001", "4003" -> BankTransactionResult.badRequest("vcb_" + resultCode, response.status());
            default -> BankTransactionResult.indeterminate("vcb_unknown_" + resultCode, response.status());
        };
    }
}
