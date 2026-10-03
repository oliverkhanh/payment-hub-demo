package com.paymenthub.hub.domain.bank;

import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import com.paymenthub.hub.domain.bank.PaymentAttemptStore.AttemptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Điều phối "Ana bấm Thanh toán": CREATED -> IN_PROGRESS -> gọi Bank -> redirect.
 *
 * ⚠️ CỐ Ý KHÔNG @Transactional. Transaction nằm gọn trong PaymentAttemptStore, còn cú
 * gọi mạng nằm NGOÀI mọi transaction (xem giải thích ở PaymentAttemptStore).
 */
@Service
public class BankPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BankPaymentService.class);

    private final BankConnectorRegistry connectors;
    private final PaymentAttemptStore attempts;
    private final HubBankProperties banks;
    private final String callbackBaseUrl;
    private final String checkoutBaseUrl;

    public BankPaymentService(BankConnectorRegistry connectors, PaymentAttemptStore attempts,
                              HubBankProperties banks,
                              @Value("${hub.bank-callback.base-url:http://localhost:8080}")
                              String callbackBaseUrl,
                              @Value("${hub.checkout.base-url:http://localhost:8080}")
                              String checkoutBaseUrl) {
        this.connectors = connectors;
        this.attempts = attempts;
        this.banks = banks;
        this.callbackBaseUrl = callbackBaseUrl;
        this.checkoutBaseUrl = checkoutBaseUrl;
    }

    /** Nơi trình duyệt phải đi tiếp. Đúng hai khả năng. */
    public sealed interface Redirect {
        /** Sang trang thanh toán của ngân hàng. URL là OPAQUE — Hub không phân tích. */
        record ToBank(String url) implements Redirect { }

        /** Quay về trang checkout kèm thông báo. */
        record ToCheckout(String notice) implements Redirect { }
    }

    public Redirect pay(String checkoutToken, String bankCode) {

        // bank_code do TRÌNH DUYỆT gửi lên ⇒ KHÔNG TIN (BR-B13). Hai lớp kiểm:
        //   ① có adapter không   ② có đang BẬT không (tắt để bảo trì vẫn còn adapter)
        BankConnector bank = connectors.forBank(bankCode);
        if (!banks.isEnabled(bankCode)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_BANK,
                    "Ngân hàng " + bankCode + " đang tạm ngưng.");
        }

        var attempt = attempts.createAttempt(checkoutToken, bankCode,
                callbackUrl(bankCode), returnUrl(checkoutToken));

        return switch (attempt) {
            case AttemptResult.AlreadySent sent -> new Redirect.ToBank(sent.redirectUrl());
            case AttemptResult.Refused refused  -> new Redirect.ToCheckout(refused.notice());
            case AttemptResult.Created created  -> callBank(bank, created);
        };
    }

    /** ⚠️ Cú gọi mạng — NGOÀI mọi transaction. Gọi xong mới mở transaction #2 để ghi. */
    private Redirect callBank(BankConnector bank, AttemptResult.Created attempt) {
        BankTransactionResult result = bank.createTransaction(attempt.request());

        attempts.completeAttempt(attempt.attemptId(), attempt.paymentId(), result);

        if (result.isAccepted()) {
            return new Redirect.ToBank(result.redirectUrl());
        }
        log.warn("Không tạo được giao dịch ở {} (outcome={}, code={})",
                bank.bankCode(), result.outcome(), result.errorCode());
        return new Redirect.ToCheckout(noticeFor(result.outcome()));
    }

    /**
     * Bank gọi NGƯỢC về Hub, server→server. Trong docker phải là service name
     * (`http://payment-hub:8080`) — `localhost` ở đây là localhost của CONTAINER BANK.
     */
    private String callbackUrl(String bankCode) {
        return callbackBaseUrl + "/bank-callbacks/" + bankCode;
    }

    /**
     * Bank đẩy TRÌNH DUYỆT của Ana về. Phải là địa chỉ máy Ana gõ được
     * (`http://localhost:8080`). Lẫn với callbackUrl là lỗi "chạy ở máy tôi thì được".
     */
    private String returnUrl(String checkoutToken) {
        return checkoutBaseUrl + "/checkout/" + checkoutToken + "/bank-return";
    }

    /** Thông báo cho Ana. KHÔNG kể mã lỗi của Bank — đó là chi tiết nội bộ. */
    private String noticeFor(BankOutcome outcome) {
        return switch (outcome) {
            case DECLINED      -> "Ngân hàng từ chối giao dịch này. Vui lòng thử lại từ đơn hàng.";
            case NOT_SENT      -> "Không kết nối được tới ngân hàng. "
                                + "Bạn có thể bấm Thanh toán lại hoặc chọn ngân hàng khác.";
            case BAD_REQUEST   -> "Không gửi được yêu cầu sang ngân hàng. Vui lòng thử lại sau.";
            case INDETERMINATE -> "Chưa nhận được phản hồi từ ngân hàng. "
                                + "Giao dịch đang được xác minh, vui lòng chờ trong ít phút.";
            case ACCEPTED      -> "";   // không bao giờ tới đây: đã chặn ở isAccepted()
        };
    }
}
