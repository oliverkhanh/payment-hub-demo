package com.paymenthub.bank.infra;

import com.paymenthub.bank.domain.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, String> {

    /** Dùng cho cả chống trùng lẫn câu hỏi đối soát của Hub. */
    Optional<BankTransaction> findByBankCodeAndIdempotencyKey(String bankCode, String idempotencyKey);

    Optional<BankTransaction> findByBankTxnIdAndBankCode(String bankTxnId, String bankCode);

    /**
     * ⭐ COMPARE-AND-SET chốt giao dịch: PENDING -> SUCCESS hoặc DECLINED.
     *
     * Mệnh đề `and t.status = :expected` là thứ bảo đảm một giao dịch chỉ được chốt
     * ĐÚNG MỘT LẦN, dù Ana bấm hai lần hay mở hai tab.
     *
     * Trả về 0 nghĩa là người khác đã chốt trước — KHÔNG phải lỗi hệ thống, mà là
     * một kết quả nghiệp vụ phải xử lý.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update BankTransaction t
              set t.status        = :next,
                  t.accountCode   = :accountCode,
                  t.declineReason = :declineReason,
                  t.finishedAt    = :now
            where t.bankTxnId = :bankTxnId
              and t.status    = :expected
           """)
    int finish(@Param("bankTxnId") String bankTxnId,
               @Param("expected") BankTransaction.Status expected,
               @Param("next") BankTransaction.Status next,
               @Param("accountCode") String accountCode,
               @Param("declineReason") String declineReason,
               @Param("now") Instant now);
}
