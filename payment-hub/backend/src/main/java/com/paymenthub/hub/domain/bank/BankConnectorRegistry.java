package com.paymenthub.hub.domain.bank;

import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chọn adapter theo bank_code — phần "Strategy" của thiết kế.
 *
 * Spring inject TẤT CẢ bean kiểu BankConnector (port) vào đây — tức là mọi
 * {@code *Adapter} trong {@code infra.bank}. Thêm ngân hàng mới = THÊM MỘT BEAN,
 * không sửa dòng nào ở chỗ khác. Không có if/else, không có switch.
 */
@Component
public class BankConnectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(BankConnectorRegistry.class);

    private final Map<String, BankConnector> byCode;

    public BankConnectorRegistry(List<BankConnector> adapters) {
        this.byCode = adapters.stream()
                .collect(Collectors.toMap(BankConnector::bankCode, Function.identity()));
        log.info("Đã nạp {} bank adapter: {}", byCode.size(), byCode.keySet());
    }

    /**
     * bank_code do TRÌNH DUYỆT gửi lên ⇒ dữ liệu không tin được (BR-B13).
     * Không khớp connector nào ⇒ từ chối, KHÔNG gọi Bank.
     */
    public BankConnector forBank(String bankCode) {
        BankConnector connector = bankCode == null ? null : byCode.get(bankCode);
        if (connector == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_BANK,
                    "Ngân hàng không được hỗ trợ: " + bankCode);
        }
        return connector;
    }

    public boolean supports(String bankCode) {
        return bankCode != null && byCode.containsKey(bankCode);
    }
}
