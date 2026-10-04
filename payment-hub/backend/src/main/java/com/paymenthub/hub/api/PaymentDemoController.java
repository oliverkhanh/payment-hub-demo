package com.paymenthub.hub.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
