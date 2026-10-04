package com.paymenthub.merchant.domain;

import com.paymenthub.merchant.api.dto.CreateOrderRequest;
import com.paymenthub.merchant.infra.OrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        var replay = orders.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return new PlaceOrderResult(replay.get(), false);
        }

        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "empty_cart", "Giỏ hàng trống, không thể checkout.");
        }

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
