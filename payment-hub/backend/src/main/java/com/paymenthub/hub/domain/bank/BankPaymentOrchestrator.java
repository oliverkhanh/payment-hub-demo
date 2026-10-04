package com.paymenthub.hub.domain.bank;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankPaymentOrchestrator {

    private final BankPaymentService bankPaymentService;
    private final BankConnectorRegistry bankConnectorRegistry;

    @Value("${hub.bank-callback.base-url:http://localhost:8080}")
    private final String bankCallbackBaseUrl;

    @Value("${hub.checkout.base-url:http://localhost:8080}")
    private final String checkoutBaseUrl;

    public sealed interface PaymentRedirect {
        record ToBank(String url) implements PaymentRedirect { }
        record ToCheckout(String notice) implements PaymentRedirect { }
    }

    public PaymentRedirect pay(String checkoutToken, String bankCode) {
        BankCallDecision decision = bankPaymentService.startBankPayment(checkoutToken, bankCode);

        return switch (decision) {
            case BankCallDecision.Stop stop -> new PaymentRedirect.ToCheckout(noticeFor(stop.reason()));
            case BankCallDecision.Proceed proceed -> callBank(proceed, checkoutToken);
        };
    }

    private PaymentRedirect callBank(BankCallDecision.Proceed proceed, String checkoutToken) {
        BankConnector connector = bankConnectorRegistry.findByBankCode(proceed.bankCode())
                .orElse(null);

        if (connector == null) {
            log.error("Payment {} đã chuyển IN_PROGRESS nhưng không có connector cho {}",
                    proceed.paymentSnapshot().paymentRef(), proceed.bankCode());
            return finish(proceed, BankTransactionResult.notSent("no_connector"));
        }

        return finish(proceed, askBank(connector, proceed, checkoutToken));
    }

    private BankTransactionResult askBank(BankConnector connector, BankCallDecision.Proceed proceed,
                                          String checkoutToken) {
        try {
            return connector.createTransaction(toRequest(proceed, checkoutToken));
        } catch (RuntimeException adapterFailure) {
            log.error("Adapter {} ném lỗi cho payment {} — không biết bank đã nhận hay chưa",
                    proceed.bankCode(), proceed.paymentSnapshot().paymentRef(), adapterFailure);
            bankPaymentService.applyBankResult(proceed.paymentId(), proceed.attemptId(),
                    BankTransactionResult.indeterminate(
                            "adapter_exception:" + adapterFailure.getClass().getSimpleName(), null));
            throw adapterFailure;
        }
    }

    private PaymentRedirect finish(BankCallDecision.Proceed proceed, BankTransactionResult result) {
        bankPaymentService.applyBankResult(proceed.paymentId(), proceed.attemptId(), result);

        if (result.isAccepted()) {
            return new PaymentRedirect.ToBank(result.redirectUrl());
        }

        log.warn("Bank {} không mở được giao dịch cho payment {}: outcome={} code={}",
                proceed.bankCode(), proceed.paymentSnapshot().paymentRef(),
                result.outcome(), result.errorCode());

        return new PaymentRedirect.ToCheckout(noticeFor(result.outcome()));
    }

    private static String noticeFor(StopReason reason) {
        return switch (reason) {
            case BANK_NOT_AVAILABLE -> "Ngân hàng không khả dụng. Vui lòng chọn lại.";
            case PAYMENT_NOT_FOUND -> "Link thanh toán không tồn tại.";
            case CHECKOUT_EXPIRED -> "Link thanh toán đã hết hạn. Vui lòng đặt lại đơn hàng.";
            case ALREADY_IN_PROGRESS -> "Giao dịch đang được xử lý. Vui lòng chờ trong ít phút.";
            case ALREADY_PAID -> "Đơn hàng này đã được thanh toán.";
            case CANCELLED -> "Bạn đã hủy giao dịch này.";
            case EARLIER_ATTEMPT_FAILED -> "Giao dịch trước đã thất bại. Vui lòng thử lại từ đơn hàng.";
            case NO_LONGER_ACTIVE -> "Link thanh toán không còn hiệu lực.";
        };
    }

    private static String noticeFor(BankOutcome outcome) {
        return switch (outcome) {
            case DECLINED -> "Ngân hàng từ chối giao dịch này. Vui lòng thử lại từ đơn hàng.";
            case NOT_SENT -> "Không kết nối được tới ngân hàng. "
                    + "Bạn có thể thử lại hoặc chọn ngân hàng khác.";
            case BAD_REQUEST -> "Không gửi được yêu cầu sang ngân hàng. Vui lòng thử lại sau.";
            case INDETERMINATE -> "Chưa nhận được phản hồi từ ngân hàng. "
                    + "Giao dịch đang được xác minh, vui lòng chờ trong ít phút.";
            case ACCEPTED -> "";
        };
    }

    private BankTransactionRequest toRequest(BankCallDecision.Proceed proceed, String checkoutToken) {
        var payment = proceed.paymentSnapshot();
        return new BankTransactionRequest(
                payment.paymentRef(),
                payment.amount(),
                payment.currency(),
                payment.merchantName(),
                proceed.idempotencyKey(),
                bankCallbackBaseUrl + "/bank-callbacks/" + proceed.bankCode(),
                checkoutBaseUrl + "/checkout/" + checkoutToken + "/bank-return",
                payment.checkoutExpiresAt());
    }
}
