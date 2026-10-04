package com.paymenthub.hub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BankCallbackHmacTest extends AbstractIntegrationTest {

    private static final String WEBHOOK_SECRET = "vcb_webhook_secret_dev_only_do_not_use_in_prod";
    private static final String HUB_TO_BANK_SECRET = "vcb_secret_dev_only_do_not_use_in_prod";
    private static final String PATH = "/bank-callbacks/VCB";

    private static final String PAYLOAD = """
            {"event_type":"BankTransaction.Succeeded","idempotency_key":"idem_khong_co_that",\
            "amount":150000,"currency":"VND"}""";

    @Test
    @DisplayName("Chữ ký đúng -> qua được filter (404 vì key không có thật, KHÔNG phải 401)")
    void chu_ky_dung_thi_qua_duoc_filter() {
        String eventId = UUID.randomUUID().toString();

        var response = post(PAYLOAD, bankHeaders(eventId, PAYLOAD, now(), WEBHOOK_SECRET, "VCB"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ATTEMPT_NOT_FOUND");
    }

    @Test
    @DisplayName("Thiếu header -> 401, không tiết lộ nguyên nhân")
    void thieu_header_thi_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Bank-Code", "VCB");

        var response = post(PAYLOAD, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error_code\":\"invalid_signature\"");
        assertThat(response.getBody()).doesNotContain("X-Bank-Signature");
    }

    @Test
    @DisplayName("Chữ ký sai -> 401")
    void chu_ky_sai_thi_401() {
        HttpHeaders headers = bankHeaders(UUID.randomUUID().toString(), PAYLOAD, now(),
                WEBHOOK_SECRET, "VCB");
        headers.set("X-Bank-Signature", "0".repeat(64));

        assertThat(post(PAYLOAD, headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Ký bằng secret chiều hub->bank -> 401 (hai chiều hai khoá)")
    void ky_bang_secret_chieu_ra_thi_401() {
        HttpHeaders headers = bankHeaders(UUID.randomUUID().toString(), PAYLOAD, now(),
                HUB_TO_BANK_SECRET, "VCB");

        assertThat(post(PAYLOAD, headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Sửa body sau khi ký -> 401 (body nằm trong niêm phong)")
    void sua_body_sau_khi_ky_thi_401() {
        HttpHeaders headers = bankHeaders(UUID.randomUUID().toString(), PAYLOAD, now(),
                WEBHOOK_SECRET, "VCB");

        String tampered = PAYLOAD.replace("150000", "999999999");

        assertThat(post(tampered, headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Đổi Idempotency-Key sau khi ký -> 401 (event_id nằm trong niêm phong)")
    void doi_idempotency_key_sau_khi_ky_thi_401() {
        HttpHeaders headers = bankHeaders(UUID.randomUUID().toString(), PAYLOAD, now(),
                WEBHOOK_SECRET, "VCB");
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        assertThat(post(PAYLOAD, headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Timestamp quá cũ -> 401 request_expired")
    void timestamp_qua_cu_thi_401() {
        String stale = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
        HttpHeaders headers = bankHeaders(UUID.randomUUID().toString(), PAYLOAD, stale,
                WEBHOOK_SECRET, "VCB");

        var response = post(PAYLOAD, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error_code\":\"request_expired\"");
    }

    @Test
    @DisplayName("Bank không có secret cấu hình -> 401")
    void bank_khong_co_secret_thi_401() {
        String path = "/bank-callbacks/KHONGCO";
        HttpHeaders headers = bankHeaders(UUID.randomUUID().toString(), PAYLOAD, now(),
                WEBHOOK_SECRET, "KHONGCO");

        var response = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(PAYLOAD, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Filter bank KHÔNG chạm vào /v1/** của merchant")
    void filter_bank_khong_cham_vao_api_merchant() {
        var response = postPayment(UUID.randomUUID().toString(), createBody("ord_bank_sep", 12000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private org.springframework.http.ResponseEntity<String> post(String body, HttpHeaders headers) {
        return rest.exchange(url(PATH), HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
    }

    private HttpHeaders bankHeaders(String eventId, String body, String timestamp,
                                    String secret, String bankCode) {
        byte[] rawBody = body.getBytes(StandardCharsets.UTF_8);
        String path = "/bank-callbacks/" + bankCode;
        String canonical = signer.canonicalString("POST", path, timestamp, eventId, rawBody);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Bank-Code", bankCode);
        headers.set("X-Bank-Timestamp", timestamp);
        headers.set("Idempotency-Key", eventId);
        headers.set("X-Bank-Signature", signer.sign(secret, canonical));
        return headers;
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }
}
