package com.paymenthub.hub.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint demo cho Increment 1 (resource server): chứng minh JWT + scope hoạt động.
 * Trả dữ liệu STUB.
 *
 * Increment 3 đã dời nó từ /v1/payments sang /internal/payments, vì /v1/** giờ thuộc biên
 * MERCHANT (xác thực HMAC), còn đây là biên NỘI BỘ (xác thực JWT từ IdP realm "machine").
 * Hai biên khác cơ chế ⇒ phải khác prefix, xem ResourceServerConfig.
 */
@RestController
@RequestMapping("/internal/payments")
public class PaymentDemoController {

    @GetMapping("/{id}")
    public Map<String, Object> getPayment(@PathVariable String id) {
        return Map.of(
                "id", id,
                "amount", 150000,
                "currency", "VND",
                "status", "AUTHORIZED",
                "_note", "stub data - Increment 1 (JWT + scope payments:read)"
        );
    }
}
