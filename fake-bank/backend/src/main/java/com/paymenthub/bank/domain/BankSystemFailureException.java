package com.paymenthub.bank.domain;

import lombok.Getter;

@Getter
public class BankSystemFailureException extends RuntimeException {

    private final String bankCode;
    private final String username;

    public BankSystemFailureException(String bankCode, String username, String message) {
        super(message);
        this.bankCode = bankCode;
        this.username = username;
    }
}
