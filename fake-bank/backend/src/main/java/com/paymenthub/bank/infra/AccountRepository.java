package com.paymenthub.bank.infra;

import com.paymenthub.bank.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByBankCodeAndUsernameAndPin(String bankCode, String username, String pin);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update Account a
              set a.balance     = a.balance - :amount,
                  a.updatedDate = :now
            where a.id      = :accountId
              and a.balance >= :amount
           """)
    int debit(@Param("accountId") Long accountId,
              @Param("amount") long amount,
              @Param("now") Instant now);
}
