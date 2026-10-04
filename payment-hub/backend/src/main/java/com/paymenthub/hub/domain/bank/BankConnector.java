package com.paymenthub.hub.domain.bank;

public interface BankConnector {

    String bankCode();

    BankTransactionResult createTransaction(BankTransactionRequest request);
}
