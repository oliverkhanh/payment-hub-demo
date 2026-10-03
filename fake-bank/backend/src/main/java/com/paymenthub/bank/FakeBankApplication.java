package com.paymenthub.bank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Fake Bank - giả lập ngân hàng/acquirer.
 * Increment 0: chỉ là skeleton (chạy được + health + migrate DB). Chưa có nghiệp vụ.
 */
@SpringBootApplication
public class FakeBankApplication {
    public static void main(String[] args) {
        SpringApplication.run(FakeBankApplication.class, args);
    }
}
