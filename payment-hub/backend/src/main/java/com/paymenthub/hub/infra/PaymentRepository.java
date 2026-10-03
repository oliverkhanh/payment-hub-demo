package com.paymenthub.hub.infra;

import com.paymenthub.hub.domain.Payment;
import com.paymenthub.hub.domain.PaymentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentRef(String paymentRef);

    /**
     * @EntityGraph: nạp luôn merchant. Trang checkout đọc payment.merchant.name SAU KHI
     * transaction đã đóng (open-in-view=false) -> LazyInitializationException nếu để LAZY.
     * Đúng bug đã gặp ở Increment 2 với order.items.
     */
    @EntityGraph(attributePaths = "merchant")
    Optional<Payment> findByCheckoutToken(String checkoutToken);

    /** Merchant chỉ được thấy payment của chính mình (tenant isolation). */
    Optional<Payment> findByPaymentRefAndMerchantId(String paymentRef, Long merchantId);

    /**
     * Payment "đang sống" của một đơn — dùng để dịch DataIntegrityViolation của
     * partial index uq_payment_active thành lỗi nghiệp vụ có nghĩa.
     */
    @Query("""
           select p from Payment p
           where p.merchant.id = :merchantId
             and p.orderCode   = :orderCode
             and p.status in :statuses
           """)
    List<Payment> findByOrder(@Param("merchantId") Long merchantId,
                              @Param("orderCode") String orderCode,
                              @Param("statuses") List<PaymentStatus> statuses);

    /**
     * ⭐ COMPARE-AND-SET: CREATED -> IN_PROGRESS, nguyên tử, một câu UPDATE.
     *
     * Vì sao KHÔNG đọc rồi gọi payment.transitionTo(IN_PROGRESS)?
     * Ana mở hai tab, bấm Thanh toán gần như cùng lúc. Cả hai SELECT đều thấy CREATED,
     * cả hai gọi transitionTo rồi commit -> HAI phiên ở Bank cho MỘT đơn hàng.
     * Mệnh đề `and p.status = CREATED` biến việc kiểm tra và việc ghi thành MỘT bước:
     * DB chỉ cho đúng một giao dịch thấy status còn là CREATED.
     *
     * Trả về số dòng bị sửa: 1 = ta thắng, 0 = người khác đã đi trước HOẶC link hết hạn.
     *
     * ⚠️ Đây là chỗ DUY NHẤT trong Hub được đổi status mà không qua Payment.transitionTo()
     * (CLAUDE.md #3). Chấp nhận được vì luật transition vẫn còn nguyên — nó nằm trong
     * chính mệnh đề WHERE, chỉ là được DB thi hành thay vì Java.
     *
     * flush/clear: Hibernate không biết câu UPDATE này sửa gì, nên phải đẩy pending
     * change xuống trước và xoá entity cũ khỏi session, nếu không đoạn sau còn đọc
     * được status=CREATED đã lỗi thời.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update Payment p
              set p.status        = com.paymenthub.hub.domain.PaymentStatus.IN_PROGRESS,
                  p.bankCode      = :bankCode,
                  p.attemptCount  = p.attemptCount + 1,
                  p.lastAttemptAt = :now,
                  p.updatedAt     = :now
            where p.id = :paymentId
              and p.status = com.paymenthub.hub.domain.PaymentStatus.CREATED
              and p.checkoutExpiresAt > :now
           """)
    int markInProgress(@Param("paymentId") Long paymentId,
                       @Param("bankCode") String bankCode,
                       @Param("now") Instant now);

    /** Job quét hết hạn. Giới hạn số dòng để một lượt quét không chạy quá lâu. */
    @Query("""
           select p from Payment p
           where p.status = com.paymenthub.hub.domain.PaymentStatus.CREATED
             and p.checkoutExpiresAt < :now
           order by p.checkoutExpiresAt asc
           limit 200
           """)
    List<Payment> findExpirable(@Param("now") Instant now);
}
