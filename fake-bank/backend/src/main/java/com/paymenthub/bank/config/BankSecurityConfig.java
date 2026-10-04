package com.paymenthub.bank.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymenthub.bank.infra.security.BankHmacAuthenticationFilter;
import com.paymenthub.bank.infra.security.BankSignatureConvention;
import com.paymenthub.bank.infra.security.HmacSigner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class BankSecurityConfig {

    @Bean
    public FilterRegistrationBean<BankHmacAuthenticationFilter> bankHmacAuthenticationFilter(
            BankProperties bankProperties, HmacSigner hmacSigner, ObjectMapper objectMapper) {

        var registration = new FilterRegistrationBean<>(
                new BankHmacAuthenticationFilter(bankProperties, hmacSigner, objectMapper));

        for (BankSignatureConvention convention : BankSignatureConvention.values()) {
            registration.addUrlPatterns(convention.createTransactionPath());
        }
        registration.setName("bankHmacAuthenticationFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
