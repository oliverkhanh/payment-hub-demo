package com.paymenthub.hub.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PaymentRefGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    public String newPaymentRef() {
        return "pay_" + randomToken(12);
    }

    public String newCheckoutToken() {
        return "ctk_" + randomToken(24);
    }

    public String newBankIdempotencyKey() {
        return "att_" + randomToken(16);
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL_SAFE.encodeToString(buffer);
    }
}
