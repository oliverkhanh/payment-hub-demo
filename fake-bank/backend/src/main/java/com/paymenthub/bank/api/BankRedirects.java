package com.paymenthub.bank.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dựng URL trang thanh toán của bank.
 *
 * ⚠️ Dùng public-base-url, KHÔNG dùng địa chỉ Hub gọi vào. Hub gọi tới
 * `http://fake-bank:8080` (tên service trong docker network) nhưng URL này sẽ chạy
 * trên TRÌNH DUYỆT của Ana — máy Ana không phân giải được `fake-bank`.
 */
@Component
public class BankRedirects {

    private final String publicBaseUrl;

    public BankRedirects(@Value("${bank.public-base-url:http://localhost:8082}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String checkoutUrl(String bankCode, String bankTxnId) {
        return publicBaseUrl + "/banks/" + bankCode + "/checkout/" + bankTxnId;
    }
}
