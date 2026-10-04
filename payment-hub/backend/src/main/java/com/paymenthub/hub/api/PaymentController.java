package com.paymenthub.hub.api;

import com.paymenthub.hub.api.dto.CreatePaymentRequest;
import com.paymenthub.hub.api.dto.PaymentResponse;
import com.paymenthub.hub.domain.Merchant;
import com.paymenthub.hub.domain.PaymentService;
import com.paymenthub.hub.infra.security.CachedBodyHttpServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Merchant merchant,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreatePaymentRequest request,
                                    HttpServletRequest httpRequest) {
        byte[] rawBody = rawBodyOf(httpRequest);
        var result = paymentService.createPayment(merchant, idempotencyKey, request, rawBody);

        return switch (result) {
            case PaymentService.Result.Created created -> ResponseEntity
                    .created(java.net.URI.create("/v1/payments/" + created.body().paymentRef()))
                    .body(created.body());

            case PaymentService.Result.Replayed replayed -> ResponseEntity
                    .ok()
                    .header("Idempotent-Replay", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(replayed.rawBody());
        };
    }

    @GetMapping("/{paymentRef}")
    public PaymentResponse get(@AuthenticationPrincipal Merchant merchant,
                               @PathVariable String paymentRef) {
        return paymentService.getForMerchant(merchant, paymentRef);
    }

    private byte[] rawBodyOf(HttpServletRequest request) {
        if (request instanceof CachedBodyHttpServletRequest cached) {
            return cached.getBody();
        }
        for (var current = request; current instanceof jakarta.servlet.http.HttpServletRequestWrapper w; ) {
            var inner = w.getRequest();
            if (inner instanceof CachedBodyHttpServletRequest cached) return cached.getBody();
            if (!(inner instanceof HttpServletRequest next)) break;
            current = next;
        }
        throw new IllegalStateException(
                "Không tìm thấy CachedBodyHttpServletRequest — filter HMAC chưa chạy?");
    }
}
