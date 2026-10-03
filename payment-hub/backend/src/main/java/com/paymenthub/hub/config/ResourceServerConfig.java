package com.paymenthub.hub.config;

import com.paymenthub.hub.api.ProblemWriter;
import com.paymenthub.hub.domain.BusinessException;
import com.paymenthub.hub.domain.ErrorCode;
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

/**
 * BA chuỗi filter, mỗi chuỗi cho MỘT loại client. Cùng khuôn mẫu Increment 1
 * (Spring Authorization Server cũng dùng 2 chuỗi: /oauth2/** và phần còn lại).
 *
 * Spring duyệt theo @Order, lấy chuỗi ĐẦU TIÊN khớp rồi DỪNG.
 * ⇒ Quy tắc: pattern HẸP + chặt chẽ đứng trước; pattern RỘNG + thoáng đứng sau.
 *   Không có @Order thì thứ tự không xác định — tệ nhất là chuỗi permitAll() lọt lên
 *   trước và API merchant chạy qua chuỗi KHÔNG có HMAC, hở mà không báo lỗi gì.
 */
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

    /** ① Merchant server -> Hub: API key + HMAC (cross-org, không dùng IdP nội bộ). */
    @Bean
    @Order(1)
    public SecurityFilterChain merchantApiChain(HttpSecurity http,
                                               MerchantHmacAuthenticationFilter hmacFilter)
            throws Exception {
        return http
                .securityMatcher("/v1/**")
                .csrf(csrf -> csrf.disable())                     // không cookie ⇒ không có gì để lợi dụng
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(hmacFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    /** ② Trình duyệt của Ana: xác thực bằng checkout_token trong URL (capability). */
    @Bean
    @Order(2)
    public SecurityFilterChain checkoutChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/checkout/**")
                .csrf(Customizer.withDefaults())                  // có form POST + cookie ⇒ BẬT CSRF
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * ③ Service nội bộ Hub gọi nhau: OAuth2 client_credentials -> JWT, validate qua JWKS
     * (Increment 1). Giữ nguyên để Inc 1 vẫn verify được, chỉ dời khỏi /v1/** vì prefix đó
     * giờ thuộc biên merchant.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        // /error PHẢI permitAll: Tomcat dispatch nội bộ sang đây khi có lỗi.
                        // Nếu nó yêu cầu xác thực thì MỌI lỗi 500 bị che thành 401 -> debug mù.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/internal/payments/**").hasAuthority("SCOPE_payments:read")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * ⚠️ BẮT BUỘC: tắt auto-registration của filter HMAC.
     *
     * Spring Boot tự đăng ký MỌI bean kiểu Filter thành servlet filter áp cho "/*" —
     * tức nó chạy cho CẢ /checkout/** (nơi Ana không có X-Signature) -> 401 cho trang
     * checkout, dù SecurityConfig không hề gắn filter đó vào chuỗi /checkout/**.
     *
     * Đăng ký với enabled=false để filter CHỈ sống trong chuỗi /v1/** qua addFilterBefore.
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<MerchantHmacAuthenticationFilter>
            disableHmacFilterAutoRegistration(MerchantHmacAuthenticationFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Lỗi xác thực ở chuỗi merchant cũng phải là problem+json, không phải HTML mặc định. */
    @Bean
    public org.springframework.security.web.AuthenticationEntryPoint problemJsonEntryPoint(
            ProblemWriter problems) {
        return (request, response, authException) -> problems.write(
                response, request.getRequestURI(),
                new BusinessException(ErrorCode.INVALID_SIGNATURE));
    }
}
