package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.bank.dto.ScbPaymentOrderRequest;
import com.paymenthub.hub.infra.bank.dto.ScbPaymentOrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScbAdapter implements BankConnector {

    private static final String BANK_CODE = "SCB";
    private static final String PATH = "/scb/openapi/v1/payment-orders";
    private static final DateTimeFormatter COMPACT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

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
        byte[] body = BankPayloads.toJsonBytes(objectMapper, envelopeOf(request));

        log.info("[SCB] POST {} payment_ref={} amt={} {} RequestID trong header envelope",
                url, request.paymentRef(), request.amount(), request.currency());

        var response = bankHttpClient.post(BANK_CODE, url, MediaType.APPLICATION_JSON, body, null);

        return translate(response);
    }

    private ScbPaymentOrderRequest envelopeOf(BankTransactionRequest request) {
        return new ScbPaymentOrderRequest(
                new ScbPaymentOrderRequest.Header(
                        request.idempotencyKey(),
                        COMPACT_TIME.format(Instant.now()),
                        "PAYMENTHUB"),
                new ScbPaymentOrderRequest.Data(
                        String.valueOf(request.amount()),
                        request.currency(),
                        request.merchantName(),
                        request.callbackUrl(),
                        request.returnUrl(),
                        COMPACT_TIME.format(request.expiresAt())));
    }

    private BankTransactionResult translate(BankHttpClient.Response response) {
        if (response.notSent()) {
            return BankTransactionResult.notSent("not_sent:" + response.failureReason());
        }
        if (response.noAnswer()) {
            return BankTransactionResult.indeterminate("no_answer:" + response.failureReason(), null);
        }

        ScbPaymentOrderResponse body =
                BankPayloads.read(objectMapper, response.body(), ScbPaymentOrderResponse.class);
        if (body == null) {
            return BankTransactionResult.indeterminate("unparseable_response", response.status());
        }

        String responseCode = body.responseCodeOrEmpty();

        if (response.ok() && "000".equals(responseCode)) {
            return BankTransactionResult.accepted(body.transactionId(), body.paymentUrl(),
                    response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        return switch (responseCode) {
            case "008", "010", "011" ->
                    BankTransactionResult.declined("scb_" + responseCode, response.status());
            case "500", "503" ->
                    BankTransactionResult.indeterminate("scb_" + responseCode, response.status());
            case "400", "401" ->
                    BankTransactionResult.badRequest("scb_" + responseCode, response.status());
            default -> BankTransactionResult.indeterminate(
                    "scb_unknown_" + responseCode, response.status());
        };
    }
}
