package com.paymenthub.bank.domain;

public sealed interface AccountCheck {

    record Valid(Long accountId, String username, long balance) implements AccountCheck { }

    record Rejected(String notice) implements AccountCheck { }
}
