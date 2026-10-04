package com.paymenthub.bank.domain;

import com.paymenthub.bank.infra.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BankTransactionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BankTransactionRepository bankTransactionRepository;

    @Transactional
    public BankTransaction openTransaction(String bankCode, NewTransaction request) {
        return bankTransactionRepository
                .findByBankCodeAndIdempotencyKey(bankCode, request.idempotencyKey())
                .orElseGet(() -> bankTransactionRepository.save(
                        new BankTransaction(bankCode, newTxnRef(bankCode), request)));
    }

    @Transactional(readOnly = true)
    public Optional<BankTransaction> findForPage(String bankCode, String bankTxnRef) {
        return bankTransactionRepository.findByBankCodeAndBankTxnRef(bankCode, bankTxnRef);
    }

    private static String newTxnRef(String bankCode) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return bankCode + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
