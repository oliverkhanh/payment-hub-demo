package com.paymenthub.hub.domain.bank;

import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import com.paymenthub.hub.domain.Payment;
import com.paymenthub.hub.domain.PaymentAttempt;
import com.paymenthub.hub.domain.PaymentStatus;
import com.paymenthub.hub.infra.PaymentAttemptRepository;
import com.paymenthub.hub.infra.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Hai transaction NGẮN kẹp lấy một cú gọi mạng DÀI.
 *
 * ⭐ Vì sao tách ra bean riêng thay vì một @Transactional bọc hết?
 * Cú gọi HTTP sang Bank mất 0,1s–5s. Giữ transaction mở suốt thời gian đó nghĩa là giữ
 * một connection Postgres suốt thời gian đó. 50 khách bấm Thanh toán cùng lúc là cạn
 * connection pool, và lúc ấy CẢ HỆ THỐNG đứng — kể cả API không liên quan.
 * Luật: KHÔNG BAO GIỜ gọi mạng ở trong transaction.
 *
 * (@Transactional chỉ có hiệu lực khi gọi QUA PROXY ⇒ phải là bean khác, không tự gọi.)
 */
@Service
public class PaymentAttemptStore {

    private static final Logger log = LoggerFactory.getLogger(PaymentAttemptStore.class);

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;

    public PaymentAttemptStore(PaymentRepository payments, PaymentAttemptRepository attempts) {
        this.payments = payments;
        this.attempts = attempts;
    }

    /**
     * Ba kết cục của việc xin quyền gọi Bank. `sealed` vì đúng ba, không bao giờ có cái
     * thứ tư ⇒ người gọi dùng `switch` và compiler kiểm hộ đã phủ hết.
     */
    public sealed interface AttemptResult {

        /** Thắng CAS, được quyền gọi Bank. */
        record Created(Long paymentId, Long attemptId, BankTransactionRequest request)
                implements AttemptResult { }

        /** Đã gửi rồi và Bank đã trả link (Ana bấm lại / hai tab) ⇒ dùng lại link cũ. */
        record AlreadySent(String redirectUrl) implements AttemptResult { }

        /** Không gọi được: link hết hạn, đã huỷ, đang xử lý mà chưa có link… */
        record Refused(String notice) implements AttemptResult { }
    }

    /**
     * TRANSACTION #1 — CAS + ghi ý định, rồi COMMIT. Chưa gọi Bank.
     *
     * ⭐ Thứ tự "ghi trước, gọi sau" là có chủ đích. Hub sập ngay sau commit mà chưa kịp
     * gọi thì dòng attempt outcome=NULL vẫn còn ⇒ job đối soát biết có giao dịch treo và
     * biết ĐI HỎI Bank bằng khoá nào.
     * Ngược lại (gọi trước, ghi sau) mà sập giữa đường thì Hub mất dấu hoàn toàn: Bank
     * có thể đã trừ tiền mà Hub không còn gì để tra.
     *
     * Đây là INTENT LOG, không phải Outbox. Outbox = "gửi lại" (không ai chờ). Ở đây Ana
     * đang đứng chờ redirect và Bank CÓ THỂ đã hành động ⇒ phải "đi hỏi", không "gửi lại".
     */
    @Transactional
    public AttemptResult createAttempt(String checkoutToken, String bankCode,
                                       String callbackUrl, String returnUrl) {

        Payment payment = payments.findByCheckoutToken(checkoutToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Link thanh toán không tồn tại."));

        Instant now = Instant.now();

        // Compare-and-set: vừa kiểm tra vừa ghi trong MỘT câu UPDATE.
        if (payments.markInProgress(payment.getId(), bankCode, now) == 0) {
            return afterLostRace(payment.getId(), now);
        }

        // Khoá idempotency sinh ĐÚNG MỘT LẦN ở đây rồi lưu lại.
        String idempotencyKey = "att_" + UUID.randomUUID();
        int attemptNo = attempts.findTopByPaymentIdOrderByAttemptNoDesc(payment.getId())
                .map(a -> a.getAttemptNo() + 1)
                .orElse(1);

        PaymentAttempt attempt = attempts.save(
                new PaymentAttempt(payment.getId(), attemptNo, bankCode, idempotencyKey));

        log.info("Ghi attempt #{} cho payment {} qua {} (khoá {})",
                attemptNo, payment.getPaymentRef(), bankCode, idempotencyKey);

        return new AttemptResult.Created(payment.getId(), attempt.getId(),
                new BankTransactionRequest(
                        payment.getPaymentRef(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getMerchant().getName(),
                        idempotencyKey,
                        callbackUrl,
                        returnUrl,
                        payment.getCheckoutExpiresAt()));
    }

    /** Thua CAS: hoặc luồng khác đã đi trước, hoặc link vừa hết hạn. Đọc lại để biết. */
    private AttemptResult afterLostRace(Long paymentId, Instant now) {
        Payment fresh = payments.findById(paymentId).orElseThrow();

        if (fresh.getStatus() == PaymentStatus.IN_PROGRESS) {
            // Luồng kia đã gọi Bank xong ⇒ dùng lại ĐÚNG link đó. Tạo giao dịch thứ hai
            // ở Bank cho cùng một đơn là cách tạo ra hai lần trừ tiền.
            return attempts.findTopByPaymentIdOrderByAttemptNoDesc(paymentId)
                    .filter(PaymentAttempt::wasAccepted)
                    .map(a -> {
                        log.info("Payment {} đã có giao dịch ở bank, dùng lại redirect_url",
                                fresh.getPaymentRef());
                        return (AttemptResult) new AttemptResult.AlreadySent(a.getRedirectUrl());
                    })
                    .orElseGet(() -> new AttemptResult.Refused(
                            "Giao dịch đang được xử lý, vui lòng chờ trong ít phút."));
        }

        if (fresh.getStatus() == PaymentStatus.CREATED && fresh.isCheckoutExpired(now)) {
            fresh.transitionTo(PaymentStatus.EXPIRED);
            return new AttemptResult.Refused(
                    "Link thanh toán đã hết hạn. Vui lòng đặt lại đơn hàng.");
        }
        return new AttemptResult.Refused("Link thanh toán không còn hiệu lực.");
    }

    /**
     * TRANSACTION #2 — ghi câu trả lời. Chạy SAU khi cú gọi mạng đã xong.
     *
     * BỐN nhánh, phân loại theo đúng một câu hỏi: ***ta BIẾT gì về số tiền?***
     *
     *   ACCEPTED      -> bank có giao dịch + link
     *                    giữ IN_PROGRESS (tạo giao dịch CHƯA phải trả tiền), lưu bank_txn_id
     *
     *   NOT_SENT      -> biết chắc bank KHÔNG NHẬN GÌ, tiền còn nguyên
     *                    ⇒ LÙI VỀ CREATED để Ana bấm lại ngay. IN_PROGRESS ở ca này là
     *                      một lời không đúng: ta đặt chỗ rồi không dùng tới.
     *
     *   DECLINED/400  -> bank ĐÃ TRẢ LỜI là không
     *                    ⇒ FAILED. Chỉ được đóng khi bank thực sự đã trả lời.
     *
     *   INDETERMINATE -> KHÔNG BIẾT GÌ (đã gửi, không đọc được trả lời)
     *                    ⇒ GIỮ NGUYÊN IN_PROGRESS, để job đối soát đi hỏi Bank.
     *                      Lùi về CREATED ở đây là mở đường cho khách trả hai lần;
     *                      đóng FAILED ở đây là tự nhận "không mất tiền" khi không biết.
     */
    @Transactional
    public void completeAttempt(Long attemptId, Long paymentId, BankTransactionResult result) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        attempt.complete(result);

        Payment payment = payments.findById(paymentId).orElseThrow();

        if (result.isAccepted()) {
            payment.attachBankTransaction(result.bankTxnId());
            log.info("Bank {} đã tạo giao dịch {} cho payment {}",
                    attempt.getBankCode(), result.bankTxnId(), payment.getPaymentRef());

        } else if (result.outcome().neverReachedBank()) {
            // Không gửi được gì ⇒ nhả chỗ, Ana bấm lại hoặc chọn bank khác được ngay.
            // Dòng attempt vẫn còn (outcome=NOT_SENT) nên lịch sử không mất.
            if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
                payment.releaseForRetry();
            }
            log.warn("Không gửi được request tới {} ({}) — payment {} trả về CREATED để thử lại",
                    attempt.getBankCode(), result.errorCode(), payment.getPaymentRef());

        } else if (result.outcome().rulesOutCharge()) {
            if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
                payment.transitionTo(PaymentStatus.FAILED);
            }
            log.warn("Bank {} từ chối dứt khoát payment {} (code={}, http={})",
                    attempt.getBankCode(), payment.getPaymentRef(),
                    result.errorCode(), result.httpStatus());

        } else {
            log.error("⚠️ IN-DOUBT: payment {} qua {} không có kết luận (code={}). "
                            + "Giữ IN_PROGRESS, chờ đối soát bằng khoá {}",
                    payment.getPaymentRef(), attempt.getBankCode(),
                    result.errorCode(), attempt.getIdempotencyKey());
        }
    }
}
