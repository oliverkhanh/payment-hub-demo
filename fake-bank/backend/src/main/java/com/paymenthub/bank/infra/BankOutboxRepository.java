package com.paymenthub.bank.infra;

import com.paymenthub.bank.domain.BankOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankOutboxRepository extends JpaRepository<BankOutboxEvent, Long> {

    /** Dùng để kiểm tra trong test và khi soi dữ liệu. Worker gửi là T4. */
    List<BankOutboxEvent> findByBankTxnIdOrderByIdAsc(String bankTxnId);
}
