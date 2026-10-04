package com.paymenthub.bank.domain;

import com.paymenthub.bank.infra.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAuthenticationService {

    public static final String INVALID_CREDENTIALS =
            "Thông tin đăng nhập không đúng. Vui lòng kiểm tra lại.";
    public static final String ACCOUNT_LOCKED =
            "Tài khoản đang bị khoá. Vui lòng liên hệ ngân hàng.";

    private final AccountRepository accountRepository;

    @Value("${bank.slow-account-delay-ms:5000}")
    private long slowAccountDelayMs;

    @Transactional(readOnly = true)
    public AccountCheck authenticate(String bankCode, String username, String pin) {
        Account account = accountRepository.findByBankCodeAndUsernameAndPin(bankCode, username, pin).orElse(null);
        if (account == null) {
            log.warn("[{}] xác thực thất bại cho username={}", bankCode, username);
            return new AccountCheck.Rejected(INVALID_CREDENTIALS);
        }

        if (!account.isActive()) {
            log.warn("[{}] tài khoản {} ở trạng thái {}", bankCode, username, account.getStatus());
            return new AccountCheck.Rejected(ACCOUNT_LOCKED);
        }

        if (account.isSlow()) {
            sleepToSimulateSlowCore(bankCode, username);
        }

        if (account.isErrorAccount()) {
            throw new BankSystemFailureException(bankCode, username,
                    "Core banking từ chối xử lý tài khoản " + username);
        }

        log.info("[{}] xác thực thành công cho {}", bankCode, username);
        return new AccountCheck.Valid(account.getId(), account.getUsername(), account.getBalance());
    }

    private void sleepToSimulateSlowCore(String bankCode, String username) {
        log.warn("[{}] tài khoản {} đánh dấu CHẬM — ngủ {}ms", bankCode, username, slowAccountDelayMs);
        try {
            Thread.sleep(slowAccountDelayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
