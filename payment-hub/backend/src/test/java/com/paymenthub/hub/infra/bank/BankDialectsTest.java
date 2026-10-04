package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.config.HubBankProperties.BankInfo;
import com.paymenthub.hub.domain.bank.BankConnector;
import com.paymenthub.hub.domain.bank.BankOutcome;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.security.HmacSigner;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BankDialectsTest {

    private static final String SECRET = "s3cret";
    private static final String API_KEY = "hub_key";

    private BankDialectStubServer stub;
    private HubBankProperties props;
    private BankHttpClient httpClient;
    private final HmacSigner hmacSigner = new HmacSigner();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        stub = new BankDialectStubServer();
        Map<String, BankInfo> banks = new HashMap<>();
        for (String code : new String[] {"VCB", "TCB", "ACB", "SCB"}) {
            banks.put(code, new BankInfo(code, true, 1, stub.baseUrl(),
                    API_KEY, SECRET, 1000, 2000, 2));
        }
        banks.put("BIDV", new BankInfo("BIDV", true, 5, stub.baseUrl(),
                API_KEY, SECRET, 500, 800, 0));
        props = new HubBankProperties(banks);
        httpClient = new BankHttpClient(props, hmacSigner, CircuitBreakerRegistry.ofDefaults());
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private BankConnector vcb() {
        return new VcbAdapter(httpClient, props, objectMapper);
    }

    private BankConnector scb() {
        return new ScbAdapter(httpClient, props, objectMapper);
    }

    private BankConnector tcb() {
        return new TcbAdapter(httpClient, props, objectMapper);
    }

    private BankConnector acb() {
        return new AcbAdapter(httpClient, props, objectMapper);
    }

    private BankConnector bidv() {
        return new BidvAdapter(httpClient, props, objectMapper);
    }

    private static BankTransactionRequest request() {
        return new BankTransactionRequest("pay_abc", 150000, "VND", "Chợ Phiên",
                "att_key_1", "http://hub/cb", "http://hub/ret",
                Instant.parse("2026-10-05T10:00:00Z"));
    }

    @Test
    @DisplayName("TCB: endpoint riêng, camelCase, txnAmount theo đơn vị chính của ISO 4217")
    void tcbSendsItsOwnShape() throws Exception {
        stub.responseBody = """
                {"status":"SUCCESS","txnRef":"BNK-T1","paymentUrl":"http://bank/t1"}""";

        BankTransactionResult result = tcb().createTransaction(request());

        assertThat(stub.path).isEqualTo("/tcb-gateway/payment/init");
        var sent = objectMapper.readTree(stub.body);
        assertThat(sent.get("txnAmount").asText()).isEqualTo("150000");
        assertThat(sent.get("ccy").asText()).isEqualTo("VND");
        assertThat(sent.get("merchantName").asText()).isEqualTo("Chợ Phiên");
        assertThat(sent.has("amount")).isFalse();
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.bankTxnId()).isEqualTo("BNK-T1");
        assertThat(result.redirectUrl()).isEqualTo("http://bank/t1");
    }

    @Test
    @DisplayName("⭐ Bank nhận request rồi IM -> INDETERMINATE, KHÔNG phải NOT_SENT")
    void readTimeoutIsIndeterminateNotNotSent() {
        stub.responseDelayMs = 1500;

        BankTransactionResult result = bidv().createTransaction(request());

        assertThat(stub.callCount.get())
                .as("bank ĐÃ nhận request — không được kết luận 'chưa gửi'")
                .isGreaterThanOrEqualTo(1);
        assertThat(result.outcome()).isEqualTo(BankOutcome.INDETERMINATE);
        assertThat(result.outcome().neverReachedBank()).isFalse();
        assertThat(result.errorCode()).startsWith("no_answer:");
    }

    @Test
    @DisplayName("⭐ TCB: số chữ số thập phân lấy theo ISO 4217 — VND 0, USD 2")
    void tcbScalesAmountByCurrencyExponent() throws Exception {
        stub.responseBody = """
                {"status":"SUCCESS","txnRef":"BNK-T2","paymentUrl":"http://bank/t2"}""";

        tcb().createTransaction(new BankTransactionRequest("pay_vnd", 150000, "VND", "Chợ Phiên",
                "att_vnd", "http://hub/cb", "http://hub/ret",
                Instant.parse("2026-10-05T10:00:00Z")));
        assertThat(objectMapper.readTree(stub.body).get("txnAmount").asText())
                .as("VND không có đơn vị phụ -> gửi nguyên 150000")
                .isEqualTo("150000");

        tcb().createTransaction(new BankTransactionRequest("pay_usd", 150000, "USD", "Chợ Phiên",
                "att_usd", "http://hub/cb", "http://hub/ret",
                Instant.parse("2026-10-05T10:00:00Z")));
        assertThat(objectMapper.readTree(stub.body).get("txnAmount").asText())
                .as("USD có 2 chữ số thập phân -> 150000 cent = 1500.00")
                .isEqualTo("1500.00");
    }

    @Test
    @DisplayName("⭐ TCB: khoá nằm trong BODY, KHÔNG gửi header -> dòng ④ chuỗi ký RỖNG")
    void tcbPutsKeyInBodyAndSignsWithEmptyLineFour() throws Exception {
        stub.responseBody = """
                {"status":"SUCCESS","txnRef":"BNK-T1","paymentUrl":"http://bank/t1"}""";

        tcb().createTransaction(request());

        assertThat(objectMapper.readTree(stub.body).get("requestId").asText())
                .isEqualTo("att_key_1");
        assertThat(stub.headers).doesNotContainKey("Idempotency-key");

        String canonical = hmacSigner.canonicalString("POST", "/tcb-gateway/payment/init",
                stub.headers.get("X-hub-timestamp"), null, stub.body);
        assertThat(hmacSigner.sign(SECRET, canonical))
                .isEqualTo(stub.headers.get("X-hub-signature"));
    }

    @Test
    @DisplayName("TCB: INSUFFICIENT_BALANCE -> DECLINED, mã lạ -> INDETERMINATE")
    void tcbTranslatesItsOwnErrorTable() {
        stub.responseStatus = 422;
        stub.responseBody = """
                {"status":"FAILED","code":"INSUFFICIENT_BALANCE"}""";
        assertThat(tcb().createTransaction(request()).outcome()).isEqualTo(BankOutcome.DECLINED);

        stub.responseBody = """
                {"status":"FAILED","code":"CHUA_TUNG_GAP"}""";
        assertThat(tcb().createTransaction(request()).outcome()).isEqualTo(BankOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("ACB: form-urlencoded, tham số HOA_GẠCH_DƯỚI, giá trị được encode")
    void acbSendsFormUrlEncoded() {
        stub.responseBody = """
                {"error_no":0,"txn_id":"BNK-A1","pay_url":"http://bank/a1"}""";

        BankTransactionResult result = acb().createTransaction(request());

        assertThat(stub.path).isEqualTo("/acb/api/v2/pay-session");
        assertThat(stub.contentType).startsWith("application/x-www-form-urlencoded");
        assertThat(stub.bodyAsString())
                .contains("AMT=150000")
                .contains("CUR_CODE=VND")
                .contains("REF_NO=att_key_1")
                .contains("MER_NAME=Ch%E1%BB%A3+Phi%C3%AAn");
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.bankTxnId()).isEqualTo("BNK-A1");
    }

    @Test
    @DisplayName("ACB: ký trên ĐÚNG byte form, dòng ④ cũng RỖNG")
    void acbSignsFormBytes() {
        stub.responseBody = """
                {"error_no":0,"txn_id":"BNK-A1","pay_url":"http://bank/a1"}""";

        acb().createTransaction(request());

        assertThat(stub.headers).doesNotContainKey("Idempotency-key");
        String canonical = hmacSigner.canonicalString("POST", "/acb/api/v2/pay-session",
                stub.headers.get("X-hub-timestamp"), null, stub.body);
        assertThat(hmacSigner.sign(SECRET, canonical))
                .isEqualTo(stub.headers.get("X-hub-signature"));
    }

    @Test
    @DisplayName("ACB: error_no 9527 -> DECLINED, số lạ -> INDETERMINATE")
    void acbTranslatesNumericCodes() {
        stub.responseStatus = 422;
        stub.responseBody = "{\"error_no\":9527}";
        assertThat(acb().createTransaction(request()).outcome()).isEqualTo(BankOutcome.DECLINED);

        stub.responseBody = "{\"error_no\":12345}";
        assertThat(acb().createTransaction(request()).outcome()).isEqualTo(BankOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("SCB: envelope LỒNG Header/Data, số tiền là CHUỖI, thời gian yyyyMMddHHmmss")
    void scbSendsNestedEnvelope() throws Exception {
        stub.responseBody = """
                {"Header":{"ResponseCode":"000","ResponseDesc":"Success"},
                 "Data":{"TransactionID":"BNK-S1","PaymentURL":"http://bank/s1"}}""";

        BankTransactionResult result = scb().createTransaction(request());

        assertThat(stub.path).isEqualTo("/scb/openapi/v1/payment-orders");

        var sent = objectMapper.readTree(stub.body);
        assertThat(sent.get("Header").get("RequestID").asText()).isEqualTo("att_key_1");
        assertThat(sent.get("Header").get("ChannelID").asText()).isEqualTo("PAYMENTHUB");
        assertThat(sent.get("Data").get("Amt").isTextual()).isTrue();
        assertThat(sent.get("Data").get("Amt").asText()).isEqualTo("150000");
        assertThat(sent.get("Data").get("MerchantNm").asText()).isEqualTo("Chợ Phiên");
        assertThat(sent.get("Data").get("ExpiryTime").asText()).isEqualTo("20261005100000");

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.bankTxnId()).isEqualTo("BNK-S1");
        assertThat(result.redirectUrl()).isEqualTo("http://bank/s1");
    }

    @Test
    @DisplayName("SCB: khoá nằm trong Header envelope, KHÔNG gửi header HTTP -> dòng ④ RỖNG")
    void scbSignsWithEmptyLineFour() {
        stub.responseBody = """
                {"Header":{"ResponseCode":"000"},"Data":{"TransactionID":"X","PaymentURL":"u"}}""";

        scb().createTransaction(request());

        assertThat(stub.headers).doesNotContainKey("Idempotency-key");
        String canonical = hmacSigner.canonicalString("POST", "/scb/openapi/v1/payment-orders",
                stub.headers.get("X-hub-timestamp"), null, stub.body);
        assertThat(hmacSigner.sign(SECRET, canonical))
                .isEqualTo(stub.headers.get("X-hub-signature"));
    }

    @Test
    @DisplayName("SCB: ResponseCode 008 -> DECLINED, mã lạ -> INDETERMINATE")
    void scbTranslatesEnvelopeCodes() {
        stub.responseStatus = 422;
        stub.responseBody = """
                {"Header":{"ResponseCode":"008","ResponseDesc":"Insufficient balance"},"Data":{}}""";
        assertThat(scb().createTransaction(request()).outcome()).isEqualTo(BankOutcome.DECLINED);

        stub.responseBody = """
                {"Header":{"ResponseCode":"777"},"Data":{}}""";
        assertThat(scb().createTransaction(request()).outcome()).isEqualTo(BankOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("Bốn bank gửi bốn hình dạng KHÁC NHAU")
    void fourBanksFourShapes() {
        stub.responseBody = "{}";
        vcb().createTransaction(request());
        String vcbBody = stub.bodyAsString();
        tcb().createTransaction(request());
        String tcbBody = stub.bodyAsString();
        acb().createTransaction(request());
        String acbBody = stub.bodyAsString();
        scb().createTransaction(request());
        String scbBody = stub.bodyAsString();

        assertThat(vcbBody).contains("\"amount\":150000").contains("merchant_name");
        assertThat(tcbBody).contains("\"txnAmount\":\"150000\"").contains("merchantName");
        assertThat(acbBody).contains("AMT=150000").doesNotContain("{");
        assertThat(scbBody).contains("\"Header\"").contains("\"Amt\":\"150000\"");

        assertThat(java.util.Set.of(vcbBody, tcbBody, acbBody, scbBody)).hasSize(4);
    }

    @Test
    @DisplayName("Bốn adapter khai đúng bank_code của mình")
    void eachAdapterDeclaresItsCode() {
        assertThat(vcb().bankCode()).isEqualTo("VCB");
        assertThat(tcb().bankCode()).isEqualTo("TCB");
        assertThat(acb().bankCode()).isEqualTo("ACB");
        assertThat(scb().bankCode()).isEqualTo("SCB");
    }
}
