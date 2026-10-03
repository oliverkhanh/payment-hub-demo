package com.paymenthub.bank.domain;

import com.paymenthub.bank.infra.BankAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác thực chủ tài khoản: có tài khoản này không → PIN có đúng không → có đang mở không.
 *
 * CHƯA đụng tới số dư và CHƯA trừ tiền — đó là việc của thành phần sau. Tách ra vì hai
 * việc trả lời hai câu hỏi khác nhau: "anh là chủ tài khoản chứ?" và "tài khoản có đủ
 * tiền chứ?". Câu đầu là xác thực, câu sau là nghiệp vụ.
 */
@Service
public class AccountAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(AccountAuthenticator.class);

    private final BankAccountRepository accounts;

    public AccountAuthenticator(BankAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Hai kết cục. `sealed` để người gọi dùng switch và compiler kiểm hộ đã phủ hết. */
    public sealed interface AuthResult {
        record Ok(BankAccount account) implements AuthResult { }
        record Rejected(DeclineReason reason) implements AuthResult { }
    }

    /**
     * ⭐ THỨ TỰ KIỂM LÀ CÓ CHỦ ĐÍCH, không được đảo:
     *
     *   ① tài khoản có tồn tại không   -> không thì trả message CHUNG
     *   ② PIN có đúng không            -> không thì trả message CHUNG (y hệt ①)
     *   ③ tài khoản có đang mở không   -> chỉ tới đây mới được nói thật
     *
     * Vì sao ③ phải sau ②? Nếu kiểm khoá trước, người gõ bừa mã tài khoản sẽ nhận được
     * "tài khoản đang bị khoá" — tức là ta vừa xác nhận tài khoản đó CÓ THẬT, dù họ
     * không hề biết PIN. Chỉ người đã chứng minh biết PIN mới xứng đáng nghe lý do thật.
     *
     * readOnly: bước này chỉ ĐỌC. Trừ tiền là transaction ghi riêng ở thành phần sau.
     */
    @Transactional(readOnly = true)
    public AuthResult authenticate(String bankCode, String accountCode, String pin) {

        var found = accounts.findByCodeAndBankCode(accountCode, bankCode);
        if (found.isEmpty()) {
            // Tài khoản của bank KHÁC cũng rơi vào đây: mỗi bank là một hệ thống riêng,
            // ACC-101 của TCB không dùng được ở cổng VCB.
            log.info("[{}] từ chối {} — {}", bankCode, accountCode,
                    DeclineReason.ACCOUNT_NOT_FOUND.code());
            return new AuthResult.Rejected(DeclineReason.ACCOUNT_NOT_FOUND);
        }

        BankAccount account = found.get();

        if (!account.pinMatches(pin)) {
            log.info("[{}] từ chối {} — {}", bankCode, accountCode,
                    DeclineReason.INVALID_PIN.code());
            return new AuthResult.Rejected(DeclineReason.INVALID_PIN);
        }

        if (!account.isActive()) {
            log.info("[{}] từ chối {} — {}", bankCode, accountCode,
                    DeclineReason.ACCOUNT_LOCKED.code());
            return new AuthResult.Rejected(DeclineReason.ACCOUNT_LOCKED);
        }

        log.info("[{}] xác thực thành công tài khoản {}", bankCode, accountCode);
        return new AuthResult.Ok(account);
    }
}
