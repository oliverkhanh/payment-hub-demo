package com.paymenthub.bank.domain;

public class CancelFailedException extends RuntimeException {

    private final String notice;

    private CancelFailedException(String message, String notice) {
        super(message);
        this.notice = notice;
    }

    public static CancelFailedException alreadySettled(String bankTxnRef) {
        return new CancelFailedException(
                "Giao dịch " + bankTxnRef + " không còn ở trạng thái PENDING",
                "Giao dịch đã được xử lý trước đó, không huỷ được nữa.");
    }

    public String getNotice() {
        return notice;
    }
}
