package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.config.HubBankProperties.BankInfo;
import com.paymenthub.hub.domain.bank.BankOutcome;
import com.paymenthub.hub.domain.bank.BankTransactionRequest;
import com.paymenthub.hub.domain.bank.BankTransactionResult;
import com.paymenthub.hub.infra.security.HmacSigner;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VcbAdapterTest {

    private static final String SECRET = "vcb_secret_dev_only_do_not_use_in_prod";
    private static final String API_KEY = "hub_key_vcb_dev";

    private HttpServer server;
    private VcbAdapter adapter;
    private final HmacSigner hmacSigner = new HmacSigner();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile int responseStatus = 201;
    private volatile String responseBody = """
            {"result_code":"00","bank_txn_id":"BNK-123","redirect_url":"http://bank/pay/BNK-123"}""";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        var bank = new BankInfo("Vietcombank", true, 1,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                API_KEY, SECRET, 1000, 2000, 2);
        var props = new HubBankProperties(Map.of("VCB", bank));
        adapter = new VcbAdapter(new BankHttpClient(props, hmacSigner, CircuitBreakerRegistry.ofDefaults()), props, objectMapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        callCount.incrementAndGet();
        receivedPath.set(exchange.getRequestURI().getRawPath());
        try (InputStream in = exchange.getRequestBody()) {
            receivedBody.set(in.readAllBytes());
        }
        var headers = new java.util.HashMap<String, String>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
        receivedHeaders.set(headers);

        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static BankTransactionRequest request() {
        return new BankTransactionRequest("pay_abc", 150000, "VND", "Chợ Phiên",
                "att_key_1", "http://hub/bank-callbacks/VCB", "http://hub/return",
                Instant.parse("2026-10-05T10:00:00Z"));
    }

    @Test
    @DisplayName("Gửi đúng endpoint, đúng body snake_case của VCB")
    void sendsVcbShapedRequest() throws Exception {
        adapter.createTransaction(request());

        assertThat(receivedPath.get()).isEqualTo("/banks/VCB/v1/transactions");

        var sent = objectMapper.readTree(receivedBody.get());
        assertThat(sent.get("amount").asLong()).isEqualTo(150000);
        assertThat(sent.get("currency").asText()).isEqualTo("VND");
        assertThat(sent.get("merchant_name").asText()).isEqualTo("Chợ Phiên");
        assertThat(sent.get("callback_url").asText()).isEqualTo("http://hub/bank-callbacks/VCB");
        assertThat(sent.get("return_url").asText()).isEqualTo("http://hub/return");
        assertThat(sent.has("expires_at")).isTrue();
    }

    @Test
    @DisplayName("Chữ ký tính trên ĐÚNG byte gửi đi, bank tính lại ra cùng kết quả")
    void signatureMatchesBytesOnTheWire() {
        adapter.createTransaction(request());

        var headers = receivedHeaders.get();
        assertThat(headers).containsEntry("X-hub-key", API_KEY);
        assertThat(headers).containsEntry("Idempotency-key", "att_key_1");

        String canonical = hmacSigner.canonicalString("POST", "/banks/VCB/v1/transactions",
                headers.get("X-hub-timestamp"), "att_key_1", receivedBody.get());

        assertThat(hmacSigner.sign(SECRET, canonical))
                .isEqualTo(headers.get("X-hub-signature"));
    }

    @Test
    @DisplayName("result_code 00 -> ACCEPTED kèm redirect_url")
    void acceptedWhenResultCodeIsZeroZero() {
        BankTransactionResult result = adapter.createTransaction(request());

        assertThat(result.outcome()).isEqualTo(BankOutcome.ACCEPTED);
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.bankTxnId()).isEqualTo("BNK-123");
        assertThat(result.redirectUrl()).isEqualTo("http://bank/pay/BNK-123");
    }

    @Test
    @DisplayName("result_code 51 -> DECLINED, không retry")
    void declinedOnInsufficientFunds() {
        responseStatus = 422;
        responseBody = "{\"result_code\":\"51\"}";

        BankTransactionResult result = adapter.createTransaction(request());

        assertThat(result.outcome()).isEqualTo(BankOutcome.DECLINED);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ Mã lỗi LẠ -> INDETERMINATE, tuyệt đối không tự suy ra DECLINED")
    void unknownCodeIsIndeterminateNotDeclined() {
        responseStatus = 422;
        responseBody = "{\"result_code\":\"7777\"}";

        BankTransactionResult result = adapter.createTransaction(request());

        assertThat(result.outcome()).isEqualTo(BankOutcome.INDETERMINATE);
        assertThat(result.errorCode()).contains("7777");
    }

    @Test
    @DisplayName("5xx -> retry, giữ NGUYÊN Idempotency-Key, kết luận INDETERMINATE")
    void retriesOnServerErrorWithSameKey() {
        responseStatus = 503;
        responseBody = "{\"result_code\":\"99\"}";

        BankTransactionResult result = adapter.createTransaction(request());

        assertThat(callCount.get()).isEqualTo(3);
        assertThat(receivedHeaders.get()).containsEntry("Idempotency-key", "att_key_1");
        assertThat(result.outcome()).isEqualTo(BankOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("Body không đọc được -> INDETERMINATE, không đoán bừa")
    void unparseableBodyIsIndeterminate() {
        responseBody = "<html>502 Bad Gateway</html>";

        BankTransactionResult result = adapter.createTransaction(request());

        assertThat(result.outcome()).isEqualTo(BankOutcome.INDETERMINATE);
        assertThat(result.errorCode()).isEqualTo("unparseable_response");
    }

    @Test
    @DisplayName("Bank không nghe -> NOT_SENT, biết chắc chưa gửi gì")
    void connectionRefusedIsNotSent() {
        var deadBank = new BankInfo("Vietcombank", true, 1, "http://127.0.0.1:1",
                API_KEY, SECRET, 500, 500, 0);
        var props = new HubBankProperties(Map.of("VCB", deadBank));
        var deadAdapter = new VcbAdapter(new BankHttpClient(props, hmacSigner, CircuitBreakerRegistry.ofDefaults()), props, objectMapper);

        BankTransactionResult result = deadAdapter.createTransaction(request());

        assertThat(result.outcome()).isEqualTo(BankOutcome.NOT_SENT);
        assertThat(result.outcome().neverReachedBank()).isTrue();
    }
}
