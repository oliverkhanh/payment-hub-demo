package com.paymenthub.bank.domain;

import com.paymenthub.bank.infra.AccountRepository;
import com.paymenthub.bank.infra.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class DebitService {

    private static final String EVENT_TYPE_SUCCEEDED = "BankTransaction.Succeeded";

    private final AccountRepository accountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final OutboxWriter outboxWriter;

    @Transactional
    public void debit(Long accountId, BankTransaction transaction) {
        Instant now = Instant.now();

        int debited = accountRepository.debit(accountId, transaction.getAmount(), now);
        if (debited != 1) {
            throw DebitFailedException.insufficientBalance(accountId, transaction.getAmount());
        }

        int settled = bankTransactionRepository.markSuccess(transaction.getId());
        if (settled != 1) {
            throw DebitFailedException.alreadySettled(transaction.getId());
        }

        OutboxEvent event = outboxWriter.write(transaction, EVENT_TYPE_SUCCEEDED,
                BankTransactionStatus.SUCCESS, now);

        log.info("Trừ {} từ tài khoản {}, giao dịch {} chuyển SUCCESS, outbox event {}",
                transaction.getAmount(), accountId, transaction.getBankTxnRef(), event.getEventId());
    }

}
