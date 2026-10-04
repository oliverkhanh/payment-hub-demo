package com.paymenthub.merchant.domain;

import com.paymenthub.merchant.infra.OrderRepository;
import com.paymenthub.merchant.infra.hub.HubClient;
import com.paymenthub.merchant.infra.hub.HubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class PaymentInitiationService {
    private static final Logger log = LoggerFactory.getLogger(PaymentInitiationService.class);

    private final OrderRepository orders;
    private final HubClient hub;
    private final HubProperties props;

    public PaymentInitiationService(OrderRepository orders, HubClient hub, HubProperties props) {
        this.orders = orders;
        this.hub = hub;
        this.props = props;
    }

    public record CheckoutHandoff(String orderCode, String paymentRef, String checkoutUrl,
                                  boolean reused) { }

    @Transactional
    public CheckoutHandoff initiate(String orderCode) {
        Order order = orders.findByOrderCode(orderCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "order_not_found", "Không tìm thấy đơn: " + orderCode));

        if (order.getStatus().isTerminal()) {
            throw new BusinessException(HttpStatus.CONFLICT, "order_not_payable",
                    "Đơn đang ở trạng thái " + order.getStatus() + ", không thanh toán được.");
        }

        if (order.getHubPaymentRef() != null) {
            var existing = hub.getPayment(order.getHubPaymentRef());
            if (existing.isPresent() && existing.get().isSuccess()) {
                String status = existing.get().text("status");
                if ("CREATED".equals(status)) {
                    return new CheckoutHandoff(orderCode, order.getHubPaymentRef(),
                            existing.get().text("checkout_url"), true);
                }
                if ("CAPTURED".equals(status)) {
                    throw new BusinessException(HttpStatus.CONFLICT, "order_already_paid",
                            "Đơn này đã được thanh toán.");
                }

                log.info("Payment {} của đơn {} đã {} -> tạo lần thử mới",
                        order.getHubPaymentRef(), orderCode, status);
                order.resetHubAttempt(UUID.randomUUID().toString());
            }
        }

        String idempotencyKey = order.hubIdempotencyKeyOrCreate(() -> UUID.randomUUID().toString());

        Map<String, Object> request = Map.of(
                "order_code", order.getOrderCode(),
                "amount", order.getTotalAmount(),
                "currency", order.getCurrency(),
                "return_url", props.returnUrlBase() + "/orders/" + order.getOrderCode(),
                "description", "Chợ Phiên - đơn " + order.getOrderCode(),
                "metadata", Map.of("merchant_order_id", String.valueOf(order.getId())));

        var response = hub.createPayment(idempotencyKey, request);

        if (response.status() == HttpStatus.CONFLICT.value()
                && "payment_already_exists".equals(response.errorCode())) {
            String existingRef = response.text("existing_payment_ref");
            log.warn("Đơn {} đã có payment {} ở Hub — dùng lại", orderCode, existingRef);
            order.attachHubPayment(existingRef, null);
            var existing = hub.getPayment(existingRef);
            String checkoutUrl = existing.map(r -> r.text("checkout_url")).orElse(null);
            order.attachHubPayment(existingRef, checkoutUrl);
            moveToAwaitingPayment(order);
            return new CheckoutHandoff(orderCode, existingRef, checkoutUrl, true);
        }

        if (!response.isSuccess()) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "hub_rejected",
                    "Payment Hub từ chối: " + response.errorCode());
        }

        order.attachHubPayment(response.text("payment_ref"), response.text("checkout_url"));
        moveToAwaitingPayment(order);

        return new CheckoutHandoff(orderCode, order.getHubPaymentRef(),
                order.getHubCheckoutUrl(), false);
    }

    private void moveToAwaitingPayment(Order order) {
        if (order.getStatus() == OrderStatus.CREATED) {
            order.transitionTo(OrderStatus.AWAITING_PAYMENT);
        }
    }
}
