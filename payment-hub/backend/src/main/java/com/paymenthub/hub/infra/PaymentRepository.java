package com.paymenthub.hub.infra;

import com.paymenthub.hub.domain.Payment;
import com.paymenthub.hub.domain.PaymentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
