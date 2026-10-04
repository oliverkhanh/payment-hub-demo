package com.paymenthub.bank.infra.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryClassificationTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void hai_xx_la_da_gui_xong(int statusCode) {
        assertThat(WebhookSender.classify(HttpStatus.valueOf(statusCode)).status())
                .isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422})
    void bon_xx_la_sai_vinh_vien(int statusCode) {
        assertThat(WebhookSender.classify(HttpStatus.valueOf(statusCode)).status())
                .isEqualTo(WebhookDeliveryStatus.PERMANENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void bon_tram_linh_tam_va_bon_hai_chin_la_loi_tam(int statusCode) {
        assertThat(WebhookSender.classify(HttpStatus.valueOf(statusCode)).status())
                .isEqualTo(WebhookDeliveryStatus.RETRYABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void nam_xx_la_loi_tam(int statusCode) {
        assertThat(WebhookSender.classify(HttpStatus.valueOf(statusCode)).status())
                .isEqualTo(WebhookDeliveryStatus.RETRYABLE);
    }

    @Test
    void detail_giu_lai_status_code_de_dieu_tra() {
        assertThat(WebhookSender.classify(HttpStatus.valueOf(503)).detail()).isEqualTo("HTTP 503");
    }

    @Test
    void loi_mang_khong_co_message_van_ghi_duoc_ten_loi() {
        ResourceAccessException boc = new ResourceAccessException(
                "I/O error on POST request", new IOException(new ConnectException()));

        assertThat(WebhookSender.transportDetail(boc)).isEqualTo("ConnectException");
    }

    @Test
    void loi_mang_co_message_thi_ghi_ca_ten_va_message() {
        ResourceAccessException boc = new ResourceAccessException(
                "I/O error on POST request", new ConnectException("Connection refused"));

        assertThat(WebhookSender.transportDetail(boc))
                .isEqualTo("ConnectException: Connection refused");
    }
}
