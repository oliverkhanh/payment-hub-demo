package com.paymenthub.bank.infra;

import com.paymenthub.bank.domain.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    Optional<BankTransaction> findByBankCodeAndIdempotencyKey(String bankCode, String idempotencyKey);

    Optional<BankTransaction> findByBankCodeAndBankTxnRef(String bankCode, String bankTxnRef);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update BankTransaction t
              set t.status = com.paymenthub.bank.domain.BankTransactionStatus.SUCCESS
            where t.id     = :transactionId
              and t.status = com.paymenthub.bank.domain.BankTransactionStatus.PENDING
           """)
    int markSuccess(@Param("transactionId") Long transactionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update BankTransaction t
              set t.status = com.paymenthub.bank.domain.BankTransactionStatus.CANCELLED
            where t.id     = :transactionId
              and t.status = com.paymenthub.bank.domain.BankTransactionStatus.PENDING
           """)
    int markCancelled(@Param("transactionId") Long transactionId);
}
