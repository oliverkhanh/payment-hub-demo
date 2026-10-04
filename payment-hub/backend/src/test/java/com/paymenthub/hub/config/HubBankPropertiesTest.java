package com.paymenthub.hub.config;

import com.paymenthub.hub.config.HubBankProperties.BankInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubBankPropertiesTest {
    private static HubBankProperties props() {
        Map<String, BankInfo> banks = new LinkedHashMap<>();

        banks.put("ACB", new BankInfo("ACB", true, 3, "http://bank:8080", "k", "s", 2000, 5000, 2));
        banks.put("VCB", new BankInfo("Vietcombank", true, 1, "http://bank:8080", "k", "s", 2000, 5000, 2));
        banks.put("MB", new BankInfo("MB Bank", false, 5, "http://bank:8080", "k", "s", 2000, 5000, 2));
        banks.put("TCB", new BankInfo("Techcombank", true, 2, "http://bank:8080", "k", "s", 2000, 5000, 2));
        banks.put("SCB", new BankInfo("Sacombank (demo lỗi)", true, 4, "http://localhost:9", "k", "s", 2000, 5000, 2));
        return new HubBankProperties(banks);
    }

    @Test
    @DisplayName("Chỉ hiện bank đang BẬT, sắp theo order — bank tắt biến mất khỏi danh sách")
    void onlyEnabledBanksInOrder() {
        assertThat(props().enabledOptions())
                .extracting(HubBankProperties.BankOption::code)
                .containsExactly("VCB", "TCB", "ACB", "SCB");
    }

    @Test
    @DisplayName("Bank đầu danh sách là cái được chọn sẵn ⇒ thứ tự phải ỔN ĐỊNH")
    void firstOptionIsDeterministic() {
        for (int i = 0; i < 20; i++) {
            assertThat(props().enabledOptions().get(0).code()).isEqualTo("VCB");
        }
    }

    @Test
    @DisplayName("Thiếu order thì xếp cuối, và các bank cùng order sắp theo mã")
    void missingOrderGoesLast() {
        Map<String, BankInfo> banks = new LinkedHashMap<>();
        banks.put("ZZZ", new BankInfo("Z Bank", true, null, "u", "k", "s", 2000, 5000, 2));
        banks.put("BBB", new BankInfo("B Bank", true, 1, "u", "k", "s", 2000, 5000, 2));
        banks.put("AAA", new BankInfo("A Bank", true, 1, "u", "k", "s", 2000, 5000, 2));

        assertThat(new HubBankProperties(banks).enabledOptions())
                .extracting(HubBankProperties.BankOption::code)
                .containsExactly("AAA", "BBB", "ZZZ");
    }

    @Test
    @DisplayName("⭐ isEnabled: bank TẮT bị từ chối kể cả khi client tự gửi đúng mã")
    void disabledBankIsRejectedEvenIfClientKnowsTheCode() {
        HubBankProperties p = props();

        assertThat(p.isEnabled("VCB")).isTrue();

        assertThat(p.isEnabled("MB")).isFalse();

        assertThat(p.isEnabled("HACKER_BANK")).isFalse();
        assertThat(p.isEnabled(null)).isFalse();

        assertThat(p.isEnabled("vcb")).isFalse();
    }

    @Test
    @DisplayName("BankOption KHÔNG mang base-url ra trình duyệt")
    void optionDoesNotLeakBaseUrl() {
        assertThat(HubBankProperties.BankOption.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("code", "displayName");
    }

    @Test
    @DisplayName("Thiếu cấu hình một bank là lỗi triển khai ⇒ nổ ngay, không âm thầm")
    void requireThrowsOnMissingConfig() {
        assertThatThrownBy(() -> props().require("KHONGCO"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hub.banks.KHONGCO");
    }

    @Test
    @DisplayName("Không cấu hình bank nào thì trả danh sách rỗng, không nổ NPE")
    void nullBanksIsSafe() {
        HubBankProperties empty = new HubBankProperties(null);
        assertThat(empty.enabledOptions()).isEmpty();
        assertThat(empty.isEnabled("VCB")).isFalse();
    }
}
