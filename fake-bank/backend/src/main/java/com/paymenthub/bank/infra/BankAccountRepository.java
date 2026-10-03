package com.paymenthub.bank.infra;

import com.paymenthub.bank.domain.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, String> {

    Optional<BankAccount> findByCodeAndBankCode(String code, String bankCode);

    /** Hiện bảng "tài khoản thử nghiệm" ở cuối trang thanh toán (giống Stripe test cards). */
    List<BankAccount> findByBankCodeOrderByCode(String bankCode);

    /**
     * ⭐ COMPARE-AND-SET trừ tiền. Xem docs/notes/compare-and-set.md
     *
     * Ba thứ bắt buộc, thiếu cái nào cũng hỏng:
     *   ① điều kiện `balance >= :amount` nằm TRONG câu lệnh, không nằm ở `if` của Java
     *   ② `balance = balance - :amount` là phép trừ TƯƠNG ĐỐI, không phải gán tuyệt đối
     *   ③ số dòng trả về CHÍNH LÀ câu trả lời: 0 = không đủ tiền, 1 = đã trừ đúng một lần
     *
     * Đọc số dư rồi `if (balance >= amount)` rồi mới UPDATE là sai: hai người cùng rút sẽ
     * cùng đọc giá trị cũ và cùng ghi đè nhau. Đo thật bằng ./scripts/cas-experiment.sh.
     *
     * clearAutomatically: câu UPDATE này Hibernate không biết nó sửa gì, nên phải xoá
     * entity cũ khỏi session — nếu không, lần đọc sau vẫn thấy số dư đã lỗi thời.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update BankAccount a
              set a.balance = a.balance - :amount
            where a.code = :code
              and a.balance >= :amount
           """)
    int debit(@Param("code") String code, @Param("amount") long amount);
}
