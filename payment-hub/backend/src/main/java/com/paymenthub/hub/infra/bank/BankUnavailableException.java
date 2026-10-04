package com.paymenthub.hub.infra.bank;

public class BankUnavailableException extends RuntimeException {

    public BankUnavailableException(String bankCode, String reason) {
        super("Bank " + bankCode + " không phục vụ được: " + reason);
    }
}
