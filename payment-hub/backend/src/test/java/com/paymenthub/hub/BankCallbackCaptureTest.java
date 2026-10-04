package com.paymenthub.hub;

import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.domain.bank.BankCallDecision;
import com.paymenthub.hub.domain.bank.BankPaymentService;
import com.paymenthub.hub.infra.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BankCallbackCaptureTest extends AbstractIntegrationTest {

    private static final String WEBHOOK_SECRET = "vcb_webhook_secret_dev_only_do_not_use_in_prod";
    private static final long AMOUNT = 150000L;

    @Autowired BankPaymentService bankPaymentService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @DisplayName("Callback hợp lệ -> 200 CAPTURED, payment chuyển CAPTURED")
    void callback_hop_le_thi_capture() {
        Claim claim = startAttempt();

        ResponseEntity<String> response = postCallback(claim.idempotencyKey(), AMOUNT, "VND");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"CAPTURED\"");
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("Callback trùng -> vẫn 200, KHÔNG xử lý lại")
    void callback_trung_khong_xu_ly_hai_lan() {
        Claim claim = startAttempt();

        postCallback(claim.idempotencyKey(), AMOUNT, "VND");
        ResponseEntity<String> lanHai = postCallback(claim.idempotencyKey(), AMOUNT, "VND");

        assertThat(lanHai.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lanHai.getBody()).contains("\"outcome\":\"ALREADY_SETTLED\"");
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("Lệch amount -> 422, TUYỆT ĐỐI không capture")
    void lech_amount_thi_khong_capture() {
        Claim claim = startAttempt();

        ResponseEntity<String> response =
                postCallback(claim.idempotencyKey(), AMOUNT + 1, "VND");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Lệch currency -> 422, TUYỆT ĐỐI không capture")
    void lech_currency_thi_khong_capture() {
        Claim claim = startAttempt();

        ResponseEntity<String> response = postCallback(claim.idempotencyKey(), AMOUNT, "USD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("idempotency_key không tồn tại -> 404")
    void key_khong_ton_tai_thi_404() {
        ResponseEntity<String> response =
                postCallback("idem_khong_co_that_" + UUID.randomUUID(), AMOUNT, "VND");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Bank ký là TCB nhưng attempt thuộc VCB -> 404, không capture nhầm")
    void bank_khac_khong_capture_duoc_attempt_cua_bank_kia() {
        Claim claim = startAttempt();

        String body = callbackBody(claim.idempotencyKey(), AMOUNT, "VND",
                "BankTransaction.Succeeded");
        HttpHeaders headers = signedBankHeaders("TCB", body,
                "tcb_webhook_secret_dev_only_do_not_use_in_prod");

        ResponseEntity<String> response = rest.exchange(url("/bank-callbacks/TCB"),
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Callback Cancelled -> 200, payment chuyển CANCELLED")
    void callback_cancelled_thi_huy_payment() {
        Claim claim = startAttempt();

        ResponseEntity<String> response =
                postCallback(claim.idempotencyKey(), AMOUNT, "VND", "BankTransaction.Cancelled");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"CANCELLED\"");
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("Callback Cancelled trùng -> vẫn 200, không xử lý lại")
    void callback_cancelled_trung_van_200() {
        Claim claim = startAttempt();

        postCallback(claim.idempotencyKey(), AMOUNT, "VND", "BankTransaction.Cancelled");
        ResponseEntity<String> lanHai =
                postCallback(claim.idempotencyKey(), AMOUNT, "VND", "BankTransaction.Cancelled");

        assertThat(lanHai.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lanHai.getBody()).contains("\"outcome\":\"ALREADY_SETTLED\"");
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("Đã CAPTURED rồi mà nhận Cancelled -> 409, không đảo ngược")
    void da_captured_thi_khong_cancel_nguoc_duoc() {
        Claim claim = startAttempt();
        postCallback(claim.idempotencyKey(), AMOUNT, "VND");

        ResponseEntity<String> response =
                postCallback(claim.idempotencyKey(), AMOUNT, "VND", "BankTransaction.Cancelled");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("event_type lạ -> 422, không đổi gì")
    void event_type_la_thi_422() {
        Claim claim = startAttempt();

        ResponseEntity<String> response =
                postCallback(claim.idempotencyKey(), AMOUNT, "VND", "BankTransaction.KhongBiet");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(claim.paymentId())).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    private record Claim(Long paymentId, String idempotencyKey) { }

    private Claim startAttempt() {
        var created = postPayment(UUID.randomUUID().toString(),
                createBody("ord_cb_" + UUID.randomUUID(), AMOUNT));
        String checkoutUrl = HmacAuthenticationTest.extract(created.getBody(), "checkout_url");
        String token = checkoutUrl.substring(checkoutUrl.lastIndexOf('/') + 1);

        BankCallDecision decision = bankPaymentService.startBankPayment(token, "VCB");
        BankCallDecision.Proceed proceed = (BankCallDecision.Proceed) decision;

        return new Claim(proceed.paymentId(), proceed.idempotencyKey());
    }

    private PaymentStatus statusOf(Long paymentId) {
        return new TransactionTemplate(txManager).execute(status ->
                paymentRepository.findById(paymentId).orElseThrow().getStatus());
    }

    private ResponseEntity<String> postCallback(String idempotencyKey, long amount,
                                                String currency) {
        return postCallback(idempotencyKey, amount, currency, "BankTransaction.Succeeded");
    }

    private ResponseEntity<String> postCallback(String idempotencyKey, long amount,
                                                String currency, String eventType) {
        String body = callbackBody(idempotencyKey, amount, currency, eventType);
        HttpHeaders headers = signedBankHeaders("VCB", body, WEBHOOK_SECRET);

        return rest.exchange(url("/bank-callbacks/VCB"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static String callbackBody(String idempotencyKey, long amount, String currency,
                                       String eventType) {
        return """
               {"event_id":"%s","event_type":"%s",\
               "occurred_at":"2026-10-20T00:00:00Z","bank_code":"VCB",\
               "bank_txn_id":"VCB-TEST","idempotency_key":"%s",\
               "amount":%d,"currency":"%s","status":"SUCCESS"}"""
                .formatted(UUID.randomUUID(), eventType, idempotencyKey, amount, currency);
    }

    private HttpHeaders signedBankHeaders(String bankCode, String body, String secret) {
        String eventId = HmacAuthenticationTest.extract(body, "event_id");
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String path = "/bank-callbacks/" + bankCode;
        String canonical = signer.canonicalString("POST", path, timestamp, eventId,
                body.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Bank-Code", bankCode);
        headers.set("X-Bank-Timestamp", timestamp);
        headers.set("Idempotency-Key", eventId);
        headers.set("X-Bank-Signature", signer.sign(secret, canonical));
        return headers;
    }
}
