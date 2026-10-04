package com.paymenthub.bank.domain;

import lombok.Getter;

@Getter
public class DebitFailedException extends RuntimeException {

    private final String notice;

    private DebitFailedException(String notice, String message) {
        super(message);
        this.notice = notice;
    }

    public static DebitFailedException insufficientBalance(Long accountId, long amount) {
        return new DebitFailedException(
                "Số dư không đủ để thực hiện giao dịch. Vui lòng dùng tài khoản khác.",
                "Trừ tiền thất bại: tài khoản " + accountId + " không đủ " + amount);
    }

    public static DebitFailedException alreadySettled(Long transactionId) {
        return new DebitFailedException(
                "Giao dịch này đã được xử lý trước đó. Vui lòng kiểm tra lại đơn hàng.",
                "Trừ tiền thất bại: giao dịch " + transactionId + " không còn ở trạng thái PENDING");
    }
}
