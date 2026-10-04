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

    @EntityGraph(attributePaths = "merchant")
    Optional<Payment> findByCheckoutToken(String checkoutToken);

    Optional<Payment> findByPaymentRefAndMerchantId(String paymentRef, Long merchantId);

    @Query("""
           select p from Payment p
           where p.merchant.id = :merchantId
             and p.orderCode   = :orderCode
             and p.status in :statuses
           """)
    List<Payment> findByOrder(@Param("merchantId") Long merchantId,
                              @Param("orderCode") String orderCode,
                              @Param("statuses") List<PaymentStatus> statuses);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update Payment p
              set p.status    = com.paymenthub.hub.domain.PaymentStatus.IN_PROGRESS,
                  p.updatedAt = :now
            where p.id     = :paymentId
              and p.status = com.paymenthub.hub.domain.PaymentStatus.CREATED
           """)
    int markInProgress(@Param("paymentId") Long paymentId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update Payment p
              set p.status    = com.paymenthub.hub.domain.PaymentStatus.CREATED,
                  p.updatedAt = :now
            where p.id     = :paymentId
              and p.status = com.paymenthub.hub.domain.PaymentStatus.IN_PROGRESS
           """)
    int releaseClaim(@Param("paymentId") Long paymentId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update Payment p
              set p.status    = com.paymenthub.hub.domain.PaymentStatus.FAILED,
                  p.updatedAt = :now
            where p.id     = :paymentId
              and p.status = com.paymenthub.hub.domain.PaymentStatus.IN_PROGRESS
           """)
    int markFailed(@Param("paymentId") Long paymentId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update Payment p
              set p.status    = com.paymenthub.hub.domain.PaymentStatus.CAPTURED,
                  p.updatedAt = :now
            where p.id     = :paymentId
              and p.status = com.paymenthub.hub.domain.PaymentStatus.IN_PROGRESS
           """)
    int markCaptured(@Param("paymentId") Long paymentId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           update Payment p
              set p.status    = com.paymenthub.hub.domain.PaymentStatus.CANCELLED,
                  p.updatedAt = :now
            where p.id     = :paymentId
              and p.status = com.paymenthub.hub.domain.PaymentStatus.IN_PROGRESS
           """)
    int markCancelled(@Param("paymentId") Long paymentId, @Param("now") Instant now);

    @Query("""
           select p from Payment p
           where p.status = com.paymenthub.hub.domain.PaymentStatus.CREATED
             and p.checkoutExpiresAt < :now
           order by p.checkoutExpiresAt asc
           limit 200
           """)
    List<Payment> findExpirable(@Param("now") Instant now);
}
