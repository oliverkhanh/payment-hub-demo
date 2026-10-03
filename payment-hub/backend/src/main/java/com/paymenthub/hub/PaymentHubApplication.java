package com.paymenthub.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment Hub - hệ thống chính (modular monolith).
 * Increment 0: skeleton. Increment 3: khởi tạo thanh toán + hosted checkout.
 * Payment/webhook/ledger/refund... là module nội bộ, thêm dần theo increment.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentHubApplication.class, args);
    }
}
