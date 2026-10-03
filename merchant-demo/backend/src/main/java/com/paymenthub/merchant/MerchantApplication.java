package com.paymenthub.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Merchant Demo - website bán hàng giả lập (công cụ tạo dữ liệu & demo thanh toán).
 * Increment 0: chỉ skeleton. Catalog/cart/order sẽ thêm ở Increment 2.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MerchantApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantApplication.class, args);
    }
}
