package com.paymenthub.merchant.api;

import com.paymenthub.merchant.api.dto.CheckoutHandoffResponse;
import com.paymenthub.merchant.api.dto.CreateOrderRequest;
import com.paymenthub.merchant.api.dto.OrderResponse;
import com.paymenthub.merchant.domain.OrderService;
import com.paymenthub.merchant.domain.PaymentInitiationService;
import com.paymenthub.merchant.domain.PlaceOrderResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orders")
public class OrderController {
    private final OrderService orders;
    private final PaymentInitiationService payments;

    public OrderController(OrderService orders, PaymentInitiationService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateOrderRequest request) {
        PlaceOrderResult result = orders.placeOrder(idempotencyKey, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(OrderResponse.from(result.order()));
    }

    @GetMapping("/{orderCode}")
    public OrderResponse get(@PathVariable String orderCode) {
        return OrderResponse.from(orders.getByCode(orderCode));
    }

    @PostMapping("/{orderCode}/payment")
    public CheckoutHandoffResponse initiatePayment(@PathVariable String orderCode) {
        var handoff = payments.initiate(orderCode);
        return new CheckoutHandoffResponse(handoff.orderCode(), handoff.paymentRef(),
                handoff.checkoutUrl(), handoff.reused());
    }
}
