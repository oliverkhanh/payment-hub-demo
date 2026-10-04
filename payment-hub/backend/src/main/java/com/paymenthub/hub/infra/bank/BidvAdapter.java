package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.bank.dto.BidvCreateOrderRequest;
import com.paymenthub.hub.infra.bank.dto.BidvCreateOrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BidvAdapter implements BankConnector {

    private static final String BANK_CODE = "BIDV";
    private static final String PATH = "/bidv/openapi/v1/orders";

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

        log.info("[BIDV] POST {} payment_ref={} orderAmount={} {} orderRef trong BODY",
                url, request.paymentRef(), request.amount(), request.currency());

        var response = bankHttpClient.post(BANK_CODE, url, MediaType.APPLICATION_JSON, body, null);

        return translate(response);
    }

    private BidvCreateOrderRequest payloadOf(BankTransactionRequest request) {
        return new BidvCreateOrderRequest(
                request.amount(),
                request.currency(),
                request.merchantName(),
                request.idempotencyKey(),
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

        BidvCreateOrderResponse body =
                BankPayloads.read(objectMapper, response.body(), BidvCreateOrderResponse.class);
        if (body == null) {
            return BankTransactionResult.indeterminate("unparseable_response", response.status());
        }

        String rc = body.rcOrEmpty();

        if (response.ok() && "00".equals(rc)) {
            return BankTransactionResult.accepted(body.orderId(), body.payUrl(), response.status());
        }
        if (response.serverErr()) {
            return BankTransactionResult.indeterminate("http_" + response.status(), response.status());
        }

        return switch (rc) {
            case "05", "51" -> BankTransactionResult.declined("bidv_" + rc, response.status());
            case "96" -> BankTransactionResult.indeterminate("bidv_96", response.status());
            case "400", "401" -> BankTransactionResult.badRequest("bidv_" + rc, response.status());
            default -> BankTransactionResult.indeterminate("bidv_unknown_" + rc, response.status());
        };
    }
}
