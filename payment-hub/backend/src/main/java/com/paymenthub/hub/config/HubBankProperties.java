package com.paymenthub.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cấu hình từng ngân hàng Hub kết nối tới (Increment 4).
 *
 * Để ở CONFIG, không phải bảng DB: đây là 3 dòng gần như cố định, do con người cấu hình
 * một lần — khác hẳn merchant secret (hàng nghìn, tạo/xoá liên tục) nên phải nằm ở DB.
 * Xem docs/specs/03-bank-capture-spec.md §2.5.
 *
 * ⚠️ Chiều Hub -> Bank thì BANK ra luật: api-key/api-secret ở đây do Bank CẤP cho Hub,
 * ngược hẳn với Inc 3 (Hub cấp cho merchant). Mỗi bank MỘT bộ riêng — Hub phải đối xử
 * với VCB/TCB/ACB như ba hệ thống độc lập, không dùng chung credential.
 *
 * ⚠️ DEV-ONLY: secret nằm trong config. Production: AWS Secrets Manager (CLAUDE.md #8).
 */
@ConfigurationProperties(prefix = "hub")
public record HubBankProperties(Map<String, BankInfo> banks) {

    /** `order` quyết định thứ tự hiển thị — ngân hàng đầu danh sách là cái được chọn sẵn. */
    public record BankInfo(
            String displayName,
            boolean enabled,
            Integer order,

            /** URL Hub gọi SERVER->SERVER. Trong docker là service name, không phải localhost. */
            String baseUrl,
            String apiKey,
            String apiSecret,

            /**
             * Timeout BẮT BUỘC (CLAUDE.md #6). connect ngắn (bắt tay được hay không thì
             * biết ngay), read dài hơn (Bank cần thời gian xử lý). Không có default nào
             * là "vô hạn" — gọi HTTP trần là cách chắc chắn nhất để cạn connection pool.
             */
            Integer connectTimeoutMs,
            Integer readTimeoutMs,

            /** Số lần GỬI LẠI (không tính lần đầu). Chỉ áp dụng cho 5xx/lỗi mạng. */
            Integer maxRetries
    ) {
        public int orderOrLast()     { return order == null ? Integer.MAX_VALUE : order; }
        public int connectTimeout()  { return connectTimeoutMs == null ? 2000 : connectTimeoutMs; }
        public int readTimeout()     { return readTimeoutMs == null ? 5000 : readTimeoutMs; }
        public int retries()         { return maxRetries == null ? 2 : maxRetries; }
    }

    /** Một dòng để render ra trang checkout. */
    public record BankOption(String code, String displayName) { }

    /**
     * Chỉ trả về ngân hàng đang BẬT. Tắt một bank (bảo trì) chỉ cần đổi config —
     * merchant không phải deploy lại gì, vì danh sách này thuộc về Hub (spec §2.4).
     */
    public List<BankOption> enabledOptions() {
        if (banks == null) return List.of();
        return banks.entrySet().stream()
                .filter(e -> e.getValue().enabled())
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, BankInfo> e) -> e.getValue().orderOrLast())
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new BankOption(e.getKey(), e.getValue().displayName()))
                .toList();
    }

    /** Dùng để validate bank_code do TRÌNH DUYỆT gửi lên (BR-B13: không tin client). */
    public boolean isEnabled(String bankCode) {
        var info = banks == null ? null : banks.get(bankCode);
        return info != null && info.enabled();
    }

    /**
     * Cấu hình của một bank. Thiếu cấu hình là lỗi TRIỂN KHAI, không phải lỗi người dùng
     * ⇒ ném IllegalStateException để lộ ra ngay lúc khởi động/gọi thử, không âm thầm bỏ qua.
     */
    public BankInfo require(String bankCode) {
        var info = banks == null ? null : banks.get(bankCode);
        if (info == null) {
            throw new IllegalStateException("Thiếu cấu hình hub.banks." + bankCode);
        }
        return info;
    }
}
