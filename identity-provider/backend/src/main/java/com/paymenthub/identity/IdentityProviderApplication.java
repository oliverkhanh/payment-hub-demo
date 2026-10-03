package com.paymenthub.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity Provider - Platform IAM (2 realm: machine + merchant).
 * Increment 0: chỉ skeleton. Spring Authorization Server + OAuth2 sẽ thêm ở Increment 1.
 */
@SpringBootApplication
public class IdentityProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityProviderApplication.class, args);
    }
}
