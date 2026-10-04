package com.paymenthub.merchant.domain;

import com.paymenthub.merchant.api.dto.CreateOrderRequest;
import com.paymenthub.merchant.infra.OrderRepository;
import com.paymenthub.merchant.infra.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderCreator {
    private final ProductRepository products;
    private final OrderRepository orders;

    public OrderCreator(ProductRepository products, OrderRepository orders) {
        this.products = products;
        this.orders = orders;
    }

    @Transactional
    public Order create(String idempotencyKey, CreateOrderRequest req) {
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        for (CreateOrderRequest.Line l : req.items()) {
            if (l.productId() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "invalid_line", "Dòng hàng thiếu productId.");
            }
            int qty = l.quantity() == null ? 0 : l.quantity();
            if (qty <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "invalid_quantity", "Số lượng phải > 0 (productId=" + l.productId() + ").");
            }
            qtyByProduct.merge(l.productId(), qty, Integer::sum);
        }

        String currency = null;
        var pending = new ArrayList<OrderItem>();
        for (var e : qtyByProduct.entrySet()) {
            Product p = products.findById(e.getKey())
                    .filter(Product::isActive)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                            "product_not_found", "Sản phẩm không tồn tại: " + e.getKey()));
            if (currency == null) {
                currency = p.getCurrency();
            } else if (!currency.equals(p.getCurrency())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "mixed_currency", "Đơn không được trộn nhiều loại tiền tệ.");
            }
            pending.add(new OrderItem(p.getId(), p.getName(), p.getPriceAmount(),
                    p.getCurrency(), e.getValue()));
        }

        Order order = new Order(generateOrderCode(), idempotencyKey, currency);
        pending.forEach(order::addItem);

        return orders.saveAndFlush(order);
    }

    private String generateOrderCode() {
        return "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
