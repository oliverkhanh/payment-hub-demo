package com.paymenthub.hub.domain.bank;

import com.paymenthub.hub.config.HubBankProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BankConnectorRegistry {

    private final Map<String, BankConnector> connectorsByBankCode;
    private final HubBankProperties hubBankProperties;

    public BankConnectorRegistry(List<BankConnector> bankConnectors,
                                 HubBankProperties hubBankProperties) {
        this.connectorsByBankCode = bankConnectors.stream()
                .collect(Collectors.toMap(BankConnector::bankCode, Function.identity()));
        this.hubBankProperties = hubBankProperties;
    }

    public Optional<BankConnector> findByBankCode(String bankCode) {
        return bankCode == null
                ? Optional.empty()
                : Optional.ofNullable(connectorsByBankCode.get(bankCode));
    }

    public boolean supports(String bankCode) {
        return findByBankCode(bankCode).isPresent();
    }

    @PostConstruct
    void warnAboutEnabledBanksWithoutConnector() {
        log.info("Đã nạp {} bank connector: {}",
                connectorsByBankCode.size(), connectorsByBankCode.keySet());

        hubBankProperties.enabledOptions().stream()
                .map(HubBankProperties.BankOption::code)
                .filter(code -> !connectorsByBankCode.containsKey(code))
                .forEach(code -> log.error(
                        "Bank {} đang BẬT trong config nhưng KHÔNG có connector nào nhận mã đó "
                                + "— khách chọn sẽ không thanh toán được", code));
    }
}
