package com.paymenthub.bank.infra.security;

import com.paymenthub.bank.config.BankProperties;
import com.paymenthub.bank.domain.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankWebhookSignerTest {

    private static final String HUB_TO_BANK_SECRET = "vcb_secret_dev_only_do_not_use_in_prod";
    private static final String WEBHOOK_SECRET = "vcb_webhook_secret_dev_only_do_not_use_in_prod";

    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PAYLOAD = "{\"event_id\":\"x\",\"amount\":150000}";
    private static final String DESTINATION = "http://payment-hub:8080/bank-callbacks/VCB";
    private static final Instant SIGNED_AT = Instant.parse("2026-10-13T08:00:00Z");

    private final HmacSigner hmacSigner = new HmacSigner();

    private final BankWebhookSigner signer = new BankWebhookSigner(
            propertiesWith(Map.of("VCB", WEBHOOK_SECRET)), hmacSigner);

    @Test
    void dat_du_bon_header_va_giu_nguyen_byte_payload() {
        SignedWebhook signed = signer.sign(vcbEvent(PAYLOAD), SIGNED_AT);

        assertThat(signed.url().toString()).isEqualTo(DESTINATION);
        assertThat(signed.body()).isEqualTo(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(signed.headers().getFirst(BankWebhookSigner.HEADER_BANK_CODE)).isEqualTo("VCB");
        assertThat(signed.headers().getFirst(BankWebhookSigner.HEADER_TIMESTAMP))
                .isEqualTo(String.valueOf(SIGNED_AT.getEpochSecond()));
        assertThat(signed.headers().getFirst(BankWebhookSigner.HEADER_IDEMPOTENCY))
                .isEqualTo(EVENT_ID.toString());
        assertThat(signed.headers().getContentType().toString()).startsWith("application/json");
    }

    @Test
    void chuoi_ky_la_nam_dong_POST_path_timestamp_eventId_hashBody() {
        SignedWebhook signed = signer.sign(vcbEvent(PAYLOAD), SIGNED_AT);

        String canonical = String.join("\n",
                "POST",
                "/bank-callbacks/VCB",
                String.valueOf(SIGNED_AT.getEpochSecond()),
                EVENT_ID.toString(),
                hmacSigner.sha256Hex(PAYLOAD.getBytes(StandardCharsets.UTF_8)));

        assertThat(signed.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE))
                .isEqualTo(hmacSigner.sign(WEBHOOK_SECRET, canonical));
    }

    @Test
    void khong_dung_lai_secret_cua_chieu_hub_den_bank() {
        SignedWebhook signed = signer.sign(vcbEvent(PAYLOAD), SIGNED_AT);

        BankWebhookSigner signerDungSaiKhoa = new BankWebhookSigner(
                propertiesWith(Map.of("VCB", HUB_TO_BANK_SECRET)), hmacSigner);
        SignedWebhook kySaiKhoa = signerDungSaiKhoa.sign(vcbEvent(PAYLOAD), SIGNED_AT);

        assertThat(signed.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE))
                .isNotEqualTo(kySaiKhoa.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE));
    }

    @Test
    void doi_mot_ky_tu_trong_payload_thi_doi_chu_ky() {
        SignedWebhook goc = signer.sign(vcbEvent(PAYLOAD), SIGNED_AT);
        SignedWebhook sua = signer.sign(vcbEvent(PAYLOAD.replace("150000", "150001")), SIGNED_AT);

        assertThat(goc.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE))
                .isNotEqualTo(sua.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE));
    }

    @Test
    void doi_timestamp_thi_doi_chu_ky() {
        SignedWebhook som = signer.sign(vcbEvent(PAYLOAD), SIGNED_AT);
        SignedWebhook muon = signer.sign(vcbEvent(PAYLOAD), SIGNED_AT.plusSeconds(1));

        assertThat(som.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE))
                .isNotEqualTo(muon.headers().getFirst(BankWebhookSigner.HEADER_SIGNATURE));
    }

    @Test
    void thieu_secret_webhook_thi_bao_loi_cau_hinh() {
        BankWebhookSigner signerKhongCoKhoa =
                new BankWebhookSigner(propertiesWith(Map.of()), hmacSigner);

        assertThatThrownBy(() -> signerKhongCoKhoa.sign(vcbEvent(PAYLOAD), SIGNED_AT))
                .isInstanceOf(WebhookSecretMissingException.class)
                .hasMessageContaining("bank.webhook-secrets.VCB");
    }

    private static BankProperties propertiesWith(Map<String, String> webhookSecrets) {
        return new BankProperties("http://localhost:8082", 300, Map.of(), webhookSecrets);
    }

    private static OutboxEvent vcbEvent(String payload) {
        return new OutboxEvent(EVENT_ID, "VCB", "BankTransaction", "btx_1",
                "BankTransaction.Succeeded", payload, DESTINATION, SIGNED_AT);
    }
}
