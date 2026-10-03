package com.paymenthub.merchant.domain;

import com.paymenthub.merchant.api.dto.CreateOrderRequest;
import com.paymenthub.merchant.infra.OrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Điều phối checkout. Các bài học Phase 1:
 *  - Idempotency (BR-M5): key trùng -> trả đơn cũ; chốt chặn race bằng UNIQUE(idempotency_key).
 *  - Server tính tổng (BR-M3) + chốt giá (BR-M2): nằm trong OrderCreator.
 *  - Giỏ rỗng (BR-M1): chặn ở đây.
 *
 * LƯU Ý: placeOrder KHÔNG @Transactional — để khi OrderCreator.create (tx riêng) rollback
 * vì đụng UNIQUE, việc đọc lại đơn ở đây chạy trong tx SẠCH (không bị doomed rollback-only).
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OrderCreator creator;

    public OrderService(OrderRepository orders, OrderCreator creator) {
        this.orders = orders;
        this.creator = creator;
    }

    public PlaceOrderResult placeOrder(String idempotencyKey, CreateOrderRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "idempotency_key_required", "Thiếu header Idempotency-Key.");
        }

        // (1) Idempotent replay (ca thường): key đã xử lý -> trả lại đơn cũ.
        var replay = orders.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return new PlaceOrderResult(replay.get(), false);
        }

        // (2) Giỏ rỗng -> chặn (BR-M1).
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "empty_cart", "Giỏ hàng trống, không thể checkout.");
        }

        // (3) Tạo đơn trong tx riêng; (4) nếu race đồng thời đụng UNIQUE -> đọc lại (tx sạch).
        try {
            return new PlaceOrderResult(creator.create(idempotencyKey, req), true);
        } catch (DataIntegrityViolationException race) {
            return orders.findByIdempotencyKey(idempotencyKey)
                    .map(o -> new PlaceOrderResult(o, false))
                    .orElseThrow(() -> race);
        }
    }

    @Transactional(readOnly = true)
    public Order getByCode(String orderCode) {
        return orders.findByOrderCode(orderCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "order_not_found", "Không tìm thấy đơn: " + orderCode));
    }
}
