package com.paymenthub.bank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Fake Bank - giả lập ngân hàng/acquirer.
 * Increment 4: mở phiên giao dịch cho Hub (xác thực HMAC bằng credential Bank cấp).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FakeBankApplication {
    public static void main(String[] args) {
        SpringApplication.run(FakeBankApplication.class, args);
    }
}
