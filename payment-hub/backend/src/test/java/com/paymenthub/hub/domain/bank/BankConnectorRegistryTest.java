package com.paymenthub.hub.domain.bank;

import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.config.HubBankProperties.BankInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankConnectorRegistryTest {

    private static BankConnector connectorFor(String bankCode) {
        return new BankConnector() {
            @Override public String bankCode() { return bankCode; }
            @Override public BankTransactionResult createTransaction(BankTransactionRequest r) {
                return BankTransactionResult.accepted("BNK-1", "https://bank/" + bankCode, 201);
            }
        };
    }

    private static HubBankProperties props(String... enabledCodes) {
        Map<String, BankInfo> banks = new LinkedHashMap<>();
        int order = 1;
        for (String code : enabledCodes) {
            banks.put(code, new BankInfo(code, true, order++, "http://bank:8080", "k", "s", 2000, 5000, 2));
        }
        return new HubBankProperties(banks);
    }

    @Test
    @DisplayName("Lấy đúng adapter theo bank_code, không cần if/else ở chỗ gọi")
    void picksConnectorByBankCode() {
        var registry = new BankConnectorRegistry(
                List.of(connectorFor("VCB"), connectorFor("TCB"), connectorFor("ACB")),
                props("VCB", "TCB", "ACB"));

        assertThat(registry.findByBankCode("TCB")).isPresent()
                .get().extracting(BankConnector::bankCode).isEqualTo("TCB");
        assertThat(registry.findByBankCode("ACB")).isPresent()
                .get().extracting(BankConnector::bankCode).isEqualTo("ACB");
    }

    @Test
    @DisplayName("Mã lạ hoặc null trả Optional rỗng, không ném exception")
    void unknownBankCodeReturnsEmpty() {
        var registry = new BankConnectorRegistry(List.of(connectorFor("VCB")), props("VCB"));

        assertThat(registry.findByBankCode("HACKER_BANK")).isEmpty();
        assertThat(registry.findByBankCode(null)).isEmpty();
        assertThat(registry.findByBankCode("vcb")).isEmpty();
        assertThat(registry.supports("VCB")).isTrue();
        assertThat(registry.supports("MB")).isFalse();
    }

    @Test
    @DisplayName("Thêm ngân hàng mới = thêm MỘT bean, registry tự nhận")
    void addingABankIsJustAddingABean() {
        var threeBanks = new BankConnectorRegistry(
                List.of(connectorFor("VCB"), connectorFor("TCB"), connectorFor("ACB")),
                props("VCB", "TCB", "ACB"));
        assertThat(threeBanks.supports("SCB")).isFalse();

        var fourBanks = new BankConnectorRegistry(
                List.of(connectorFor("VCB"), connectorFor("TCB"), connectorFor("ACB"),
                        connectorFor("SCB")),
                props("VCB", "TCB", "ACB", "SCB"));
        assertThat(fourBanks.supports("SCB")).isTrue();
    }

    @Test
    @DisplayName("Hai adapter cùng bank_code là lỗi cấu hình, phải nổ lúc khởi động")
    void duplicateBankCodeFailsFast() {
        assertThatThrownBy(() -> new BankConnectorRegistry(
                List.of(connectorFor("VCB"), connectorFor("VCB")), props("VCB")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Bank BẬT trong config mà thiếu adapter -> ghi log ERROR, không nuốt im lặng")
    void warnsWhenEnabledBankHasNoConnector() {
        var registry = new BankConnectorRegistry(
                List.of(connectorFor("VCB")), props("VCB", "TCB"));

        registry.warnAboutEnabledBanksWithoutConnector();

        assertThat(registry.supports("TCB")).isFalse();
    }
}
