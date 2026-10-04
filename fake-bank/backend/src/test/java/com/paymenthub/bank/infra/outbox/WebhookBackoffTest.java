package com.paymenthub.bank.infra.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookBackoffTest {

    private static final long BASE_MS = 10_000L;
    private static final long MAX_MS = 60_000L;
    private static final long NO_JITTER = 1L;

    @Test
    void tang_gap_doi_theo_so_lan_thu() {
        assertThat(OutboxRelay.backoffMillis(1, BASE_MS, MAX_MS, NO_JITTER)).isEqualTo(10_000L);
        assertThat(OutboxRelay.backoffMillis(2, BASE_MS, MAX_MS, NO_JITTER)).isEqualTo(20_000L);
        assertThat(OutboxRelay.backoffMillis(3, BASE_MS, MAX_MS, NO_JITTER)).isEqualTo(40_000L);
    }

    @Test
    void bi_chan_tran_khong_tang_vo_han() {
        assertThat(OutboxRelay.backoffMillis(4, BASE_MS, MAX_MS, NO_JITTER)).isEqualTo(MAX_MS);
        assertThat(OutboxRelay.backoffMillis(20, BASE_MS, MAX_MS, NO_JITTER)).isEqualTo(MAX_MS);
        assertThat(OutboxRelay.backoffMillis(40, BASE_MS, MAX_MS, NO_JITTER)).isEqualTo(MAX_MS);
    }

    @Test
    void jitter_lam_cac_dong_khong_cung_tinh_mot_luc() {
        long dauTien = OutboxRelay.backoffMillis(1, BASE_MS, MAX_MS, 5_000L);

        boolean coLanKhacNhau = false;
        for (int lan = 0; lan < 50 && !coLanKhacNhau; lan++) {
            coLanKhacNhau = OutboxRelay.backoffMillis(1, BASE_MS, MAX_MS, 5_000L) != dauTien;
        }

        assertThat(coLanKhacNhau).isTrue();
    }

    @Test
    void jitter_khong_bao_gio_lam_giam_khoang_cho() {
        for (int lan = 0; lan < 100; lan++) {
            assertThat(OutboxRelay.backoffMillis(2, BASE_MS, MAX_MS, 5_000L))
                    .isBetween(20_000L, 25_000L);
        }
    }
}
