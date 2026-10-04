package com.paymenthub.bank.domain;

import com.paymenthub.bank.infra.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancelService {

    private static final String EVENT_TYPE_CANCELLED = "BankTransaction.Cancelled";

    private final BankTransactionRepository bankTransactionRepository;
    private final OutboxWriter outboxWriter;

    @Transactional
    public void cancel(BankTransaction transaction) {
        int cancelled = bankTransactionRepository.markCancelled(transaction.getId());
        if (cancelled != 1) {
            throw CancelFailedException.alreadySettled(transaction.getBankTxnRef());
        }

        OutboxEvent event = outboxWriter.write(transaction, EVENT_TYPE_CANCELLED,
                BankTransactionStatus.CANCELLED, Instant.now());

        log.info("Khách huỷ giao dịch {}, outbox event {}",
                transaction.getBankTxnRef(), event.getEventId());
    }
}
