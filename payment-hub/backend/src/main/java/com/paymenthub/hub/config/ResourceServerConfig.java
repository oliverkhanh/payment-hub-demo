package com.paymenthub.hub.config;

import com.paymenthub.hub.api.ProblemWriter;
import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
import com.paymenthub.hub.infra.security.BankCallbackHmacAuthenticationFilter;
import com.paymenthub.hub.infra.security.MerchantHmacAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableWebSecurity
public class ResourceServerConfig {
    @Bean
    @Order(1)
    public SecurityFilterChain merchantApiChain(HttpSecurity http,
                                               MerchantHmacAuthenticationFilter hmacFilter)
            throws Exception {
        return http
                .securityMatcher("/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(hmacFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain checkoutChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/checkout/**")
                .csrf(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain bankCallbackChain(
            HttpSecurity http, BankCallbackHmacAuthenticationFilter bankCallbackHmacFilter)
            throws Exception {
        return http
                .securityMatcher("/bank-callbacks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(bankCallbackHmacFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()

                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/internal/payments/**").hasAuthority("SCOPE_payments:read")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<MerchantHmacAuthenticationFilter>
            disableHmacFilterAutoRegistration(MerchantHmacAuthenticationFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<BankCallbackHmacAuthenticationFilter>
            disableBankCallbackFilterAutoRegistration(BankCallbackHmacAuthenticationFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public org.springframework.security.web.AuthenticationEntryPoint problemJsonEntryPoint(
            ProblemWriter problems) {
        return (request, response, authException) -> problems.write(
                response, request.getRequestURI(),
                new BusinessException(ErrorCode.INVALID_SIGNATURE));
    }
}
