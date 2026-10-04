package com.paymenthub.hub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentApiContractTest extends AbstractIntegrationTest {
    @Test
    @DisplayName("Thiếu Idempotency-Key -> 400 (thiếu thành phần GIAO THỨC, không phải giá trị sai)")
    void missingIdempotencyKeyIsBadRequest() {
        String body = createBody("ord_nokey", 1000);
        HttpHeaders headers = signedHeaders("POST", "/v1/payments", null, body);

        var response = rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"error_code\":\"idempotency_key_required\"");
    }

    @Test
    @DisplayName("amount <= 0 -> 422 (đọc hiểu được, chỉ là sai luật) + danh sách lỗi")
    void nonPositiveAmountIsUnprocessable() {
        var response = postPayment(UUID.randomUUID().toString(), createBody("ord_bad_amount", -5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .contains("\"error_code\":\"validation_failed\"")
                .contains("\"errors\"")
                .contains("amount");
    }

    @Test
    @DisplayName("return_url ngoài whitelist -> 422 (chống open redirect: không tin client)")
    void returnUrlOutsideWhitelistIsRejected() {
        var response = postPayment(UUID.randomUUID().toString(),
                createBody("ord_evil_url", 1000, "https://evil.com/phish"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("\"error_code\":\"invalid_return_url\"");
    }

    @Test
    @DisplayName("Lỗi trả đúng application/problem+json với đủ field RFC 7807")
    void errorBodyFollowsRfc7807() {
        var response = postPayment(UUID.randomUUID().toString(),
                createBody("ord_problem", 1000, "https://evil.com"));

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .contains("\"type\":\"https://paymenthub.dev/errors/")
                .contains("\"title\":")
                .contains("\"status\":422")
                .contains("\"detail\":")
                .contains("\"instance\":\"/v1/payments\"")
                .contains("\"error_code\":");
    }

    @Test
    @DisplayName("Response 201 echo lại order_code/amount/currency và KHÔNG lộ id / checkout_token")
    void createdResponseShape() {
        var response = postPayment(UUID.randomUUID().toString(), createBody("ord_shape", 355000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();

        String body = response.getBody();
        assertThat(body)
                .contains("\"payment_ref\":\"pay_")
                .contains("\"status\":\"CREATED\"")
                .contains("\"order_code\":\"ord_shape\"")
                .contains("\"amount\":355000")
                .contains("\"currency\":\"VND\"")
                .contains("\"checkout_url\":\"http")
                .contains("\"expires_at\":");

        assertThat(body).doesNotContain("\"checkout_token\"").doesNotContain("\"id\":");
    }

    @Test
    @DisplayName("Tra payment không tồn tại -> 404 (merchant khác cũng ra 404, không 403)")
    void unknownPaymentRefIsNotFound() {
        String path = "/v1/payments/pay_khong_ton_tai";
        var response = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(signedHeaders("GET", path, null, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"error_code\":\"payment_not_found\"");
    }

    @Test
    @DisplayName("Trang checkout mở được bằng checkout_token, hiện số tiền, KHÔNG lộ payment_ref")
    void checkoutPageRendersForValidToken() {
        var created = postPayment(UUID.randomUUID().toString(), createBody("ord_page", 355000));
        var page = rest.getForEntity(localCheckoutUrl(created.getBody()), String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody())
                .contains("ord_page")
                .contains("355,000")
                .contains("Quay lại cửa hàng");
    }

    @Test
    @DisplayName("checkout_token không tồn tại -> trang 'không khả dụng', không nói token sai kiểu gì")
    void checkoutPageHidesTokenDetails() {
        var page = rest.getForEntity(url("/checkout/ctk_khong_ton_tai"), String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("Không thể tiếp tục thanh toán");
    }

    @Test
    @DisplayName("Chuỗi /v1/** không áp cho /checkout/** — trang checkout KHÔNG cần HMAC")
    void checkoutChainDoesNotRequireHmac() {
        var created = postPayment(UUID.randomUUID().toString(), createBody("ord_chain", 1000));

        var page = rest.exchange(localCheckoutUrl(created.getBody()), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
