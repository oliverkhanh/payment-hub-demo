package com.paymenthub.hub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Xác thực HMAC — mỗi test là MỘT tình huống tấn công đã phân tích trong
 * docs/notes/hmac-request-signing.md §3.
 *
 * Kẻ tấn công KHÔNG có secret ⇒ nó chỉ sửa được phần NGOÀI niêm phong rồi gửi lại.
 * Mỗi test chứng minh một thành phần thực sự nằm TRONG niêm phong.
 */
class HmacAuthenticationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("Signature đúng -> 201")
    void validSignatureIsAccepted() {
        var response = postPayment(UUID.randomUUID().toString(), createBody("ord_ok_1", 355000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"payment_ref\":\"pay_");
    }

    @Test
    @DisplayName("Thiếu X-Signature -> 401, KHÔNG tiết lộ nguyên nhân")
    void missingSignatureIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Key", API_KEY);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        var response = rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(createBody("ord_nosig", 1000), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error_code\":\"invalid_signature\"");
        // Không được nói "thiếu header nào" / "api_key có tồn tại không"
        assertThat(response.getBody()).doesNotContain("X-Signature").doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("Signature sai -> 401")
    void wrongSignatureIsRejected() {
        String key = UUID.randomUUID().toString();
        String body = createBody("ord_badsig", 1000);
        HttpHeaders headers = signedHeaders("POST", "/v1/payments", key, body);
        headers.set("X-Signature", "0".repeat(64));

        var response = rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Attack: sửa BODY sau khi ký -> 401 (body nằm trong niêm phong)")
    void tamperedBodyIsRejected() {
        String key = UUID.randomUUID().toString();
        String signedBody = createBody("ord_tamper", 1000);
        HttpHeaders headers = signedHeaders("POST", "/v1/payments", key, signedBody);

        String tamperedBody = createBody("ord_tamper", 999_999_999);   // đổi amount

        var response = rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(tamperedBody, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Attack 1: đổi PATH, giữ nguyên signature -> 401")
    void tamperedPathIsRejected() {
        String key = UUID.randomUUID().toString();
        String body = createBody("ord_path", 1000);
        // Ký cho /v1/payments rồi gửi sang đường dẫn khác
        HttpHeaders headers = signedHeaders("POST", "/v1/payments", key, body);

        var response = rest.exchange(url("/v1/payments/pay_x/something"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Attack 2: đổi Idempotency-Key để nhân bản -> 401 (khoá nằm trong niêm phong)")
    void tamperedIdempotencyKeyIsRejected() {
        String signedKey = UUID.randomUUID().toString();
        String body = createBody("ord_idem_tamper", 1000);
        HttpHeaders headers = signedHeaders("POST", "/v1/payments", signedKey, body);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());   // đổi sau khi ký

        var response = rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Attack 3: timestamp quá cũ -> 401 request_expired (request không sống mãi)")
    void staleTimestampIsRejected() {
        String key = UUID.randomUUID().toString();
        String body = createBody("ord_stale", 1000);
        String oneHourAgo = String.valueOf(System.currentTimeMillis() / 1000 - 3600);

        HttpHeaders headers = signedHeaders("POST", "/v1/payments", key, body,
                oneHourAgo, SECRET, API_KEY);   // ký ĐÚNG, chỉ là ký từ 1 giờ trước

        var response = rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error_code\":\"request_expired\"");
    }

    @Test
    @DisplayName("api_key không tồn tại -> 401 invalid_signature (KHÔNG 404)")
    void unknownApiKeyLooksIdenticalToBadSignature() {
        String key = UUID.randomUUID().toString();
        String body = createBody("ord_unknown", 1000);
        HttpHeaders headers = signedHeaders("POST", "/v1/payments", key, body,
                String.valueOf(System.currentTimeMillis() / 1000), SECRET, "mk_khong_ton_tai");

        var response = rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        // 404 sẽ là tiết lộ "api_key nào có thật" -> cho phép dò danh sách merchant
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"error_code\":\"invalid_signature\"");
    }

    @Test
    @DisplayName("GET không body: chuỗi ký dùng SHA256 của chuỗi rỗng -> vẫn verify được")
    void getWithoutBodyIsSignable() {
        String created = postPayment(UUID.randomUUID().toString(),
                createBody("ord_get_1", 12345)).getBody();
        String paymentRef = extract(created, "payment_ref");

        String path = "/v1/payments/" + paymentRef;
        var response = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(signedHeaders("GET", path, null, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"amount\":12345");
    }

    static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
