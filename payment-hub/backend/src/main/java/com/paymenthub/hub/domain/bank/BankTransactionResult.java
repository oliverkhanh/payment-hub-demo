package com.paymenthub.hub.domain.bank;

public record BankTransactionResult(String bankTxnId, String redirectUrl, BankOutcome outcome,
                                    Integer httpStatus, String errorCode) {

    public static BankTransactionResult accepted(String bankTxnId, String redirectUrl,
                                                 Integer httpStatus) {
        return new BankTransactionResult(bankTxnId, redirectUrl, BankOutcome.ACCEPTED,
                httpStatus, null);
    }

    public static BankTransactionResult declined(String errorCode, Integer httpStatus) {
        return new BankTransactionResult(null, null, BankOutcome.DECLINED, httpStatus, errorCode);
    }

    public static BankTransactionResult notSent(String errorCode) {
        return new BankTransactionResult(null, null, BankOutcome.NOT_SENT, null, errorCode);
    }

    public static BankTransactionResult indeterminate(String errorCode, Integer httpStatus) {
        return new BankTransactionResult(null, null, BankOutcome.INDETERMINATE, httpStatus, errorCode);
    }

    public static BankTransactionResult badRequest(String errorCode, Integer httpStatus) {
        return new BankTransactionResult(null, null, BankOutcome.BAD_REQUEST, httpStatus, errorCode);
    }

    public boolean isAccepted() {
        return outcome == BankOutcome.ACCEPTED && redirectUrl != null;
    }
}
