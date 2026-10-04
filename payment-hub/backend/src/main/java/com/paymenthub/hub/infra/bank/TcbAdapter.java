package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.bank.dto.TcbPaymentInitRequest;
import com.paymenthub.hub.infra.bank.dto.TcbPaymentInitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Currency;

@Slf4j
@Component
@RequiredArgsConstructor
public class TcbAdapter implements BankConnector {

    private static final String BANK_CODE = "TCB";
    private static final String PATH = "/tcb-gateway/payment/init";

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

        log.info("[TCB] POST {} payment_ref={} txnAmount={} {} requestId trong BODY",
                url, request.paymentRef(), toMajorUnit(request.amount(), request.currency()), request.currency());

        var response = bankHttpClient.post(BANK_CODE, url, MediaType.APPLICATION_JSON, body, null);

        return translate(response);
    }

    private TcbPaymentInitRequest payloadOf(BankTransactionRequest request) {
        return new TcbPaymentInitRequest(
                toMajorUnit(request.amount(), request.currency()),
                request.currency(),
                request.merchantName(),
                request.idempotencyKey(),
                request.callbackUrl(),
                request.returnUrl(),
                request.expiresAt().getEpochSecond());
    }

    private static String toMajorUnit(long minorUnit, String currencyCode) {
        int fractionDigits = Math.max(Currency.getInstance(currencyCode).getDefaultFractionDigits(), 0);
        return BigDecimal.valueOf(minorUnit).movePointLeft(fractionDigits).toPlainString();
    }

    private BankTransactionResult translate(BankHttpClient.Response response) {
        if (response.notSent()) {
            return BankTransactionResult.notSent("not_sent:" + response.failureReason());
        }
        if (response.noAnswer()) {
            return BankTransactionResult.indeterminate("no_answer:" + response.failureReason(), null);
        }

        TcbPaymentInitResponse body =
                BankPayloads.read(objectMapper, response.body(), TcbPaymentInitResponse.class);
        if (body == null) {
            return BankTransactionResult.indeterminate("unparseable_response", response.status());
        }

        if (response.ok() && body.isSuccess()) {
            return BankTransactionResult.accepted(body.txnRef(), body.paymentUrl(),
                    response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        String code = body.codeOrEmpty();
        return switch (code) {
            case "INSUFFICIENT_BALANCE", "ACCOUNT_LOCKED", "LIMIT_EXCEEDED" ->
                    BankTransactionResult.declined("tcb_" + code, response.status());
            case "SYSTEM_BUSY", "TIMEOUT" ->
                    BankTransactionResult.indeterminate("tcb_" + code, response.status());
            case "INVALID_REQUEST", "SIGNATURE_INVALID" ->
                    BankTransactionResult.badRequest("tcb_" + code, response.status());
            default -> BankTransactionResult.indeterminate("tcb_unknown_" + code, response.status());
        };
    }
}
