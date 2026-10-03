package com.paymenthub.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;

import java.io.InputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;

/**
 * Cấu hình Authorization Server (Increment 1 - realm machine).
 * - Cấp JWT qua grant client_credentials
 * - Client registry lưu JDBC (bảng oauth2_registered_client, tạo bởi Flyway)
 * - Ký JWT bằng RSA (RS256) từ keystore PKCS12 (persist)
 */
@Configuration
public class AuthorizationServerConfig {

    /** Filter chain 1: các endpoint của Authorization Server (/oauth2/token, /oauth2/jwks...). */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        return http.build();
    }

    /** Filter chain 2: mọi request còn lại — actuator để public cho healthcheck. */
    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }

    /** Client registry: JDBC. Bảng oauth2_registered_client do Flyway tạo (V2). */
    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    /** Encoder cho client secret — lưu dạng hash BCrypt, không plaintext. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Signing key RS256: load từ keystore PKCS12 (persist → JWKS ổn định qua restart). */
    @Bean
    public JWKSource<SecurityContext> jwkSource(
            @Value("${idp.jwk.keystore-location}") String keystoreLocation,
            @Value("${idp.jwk.keystore-password}") String keystorePassword,
            @Value("${idp.jwk.key-alias}") String keyAlias,
            ResourceLoader resourceLoader) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = resourceLoader.getResource(keystoreLocation).getInputStream()) {
            keyStore.load(is, keystorePassword.toCharArray());
        }
        RSAKey rsaKey = RSAKey.load(keyStore, keyAlias, keystorePassword.toCharArray());
        // Gắn keyID = alias → JWKS công bố kèm kid (phục vụ key rotation về sau)
        RSAKey withKid = new RSAKey.Builder(rsaKey).keyID(keyAlias).build();
        return new ImmutableJWKSet<>(new JWKSet(withKid));
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    /**
     * Seed 2 client demo (internal service client) — idempotent.
     * Lưu ý thiết kế: bảng do Flyway tạo, nhưng DỮ LIỆU client seed bằng code
     * (repo.save) vì cột client_settings/token_settings là JSON, viết tay SQL rất dễ sai.
     */
    @Bean
    public ApplicationRunner seedClients(RegisteredClientRepository repo, PasswordEncoder encoder) {
        return args -> {
            saveIfAbsent(repo, encoder, "hub-webhook", "hub-webhook-secret", "payments:read");
            saveIfAbsent(repo, encoder, "hub-ledger", "hub-ledger-secret", "ledger:write");
        };
    }

    private void saveIfAbsent(RegisteredClientRepository repo, PasswordEncoder encoder,
                              String clientId, String secret, String scope) {
        if (repo.findByClientId(clientId) != null) {
            return;
        }
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(encoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(scope)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))   // TTL ngắn
                        .build())
                .build();
        repo.save(client);
    }
}
