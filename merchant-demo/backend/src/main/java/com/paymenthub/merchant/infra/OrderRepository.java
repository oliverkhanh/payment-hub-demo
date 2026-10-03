package com.paymenthub.merchant.infra;

import com.paymenthub.merchant.domain.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // Eager-load items để map DTO an toàn ngoài transaction (tránh LazyInitializationException).
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderCode(String orderCode);
}
