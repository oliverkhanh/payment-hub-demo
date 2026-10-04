package com.paymenthub.hub;

import com.paymenthub.hub.infra.security.HmacSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {
    protected static final String API_KEY = "mk_dev_demo";
    protected static final String SECRET = "sk_dev_demo_secret_do_not_use_in_prod";
    protected static final String RETURN_URL = "https://shop.example.com/orders/result";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("hub_db")
                    .withUsername("hub_user")
                    .withPassword("hub_pass");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("hub.jobs.expiry.fixed-delay-ms", () -> "3600000");
        registry.add("hub.jobs.idempotency-cleanup.fixed-delay-ms", () -> "3600000");
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected HmacSigner signer;

    protected HttpHeaders signedHeaders(String method, String path, String idempotencyKey,
                                        String body) {
        return signedHeaders(method, path, idempotencyKey, body,
                String.valueOf(System.currentTimeMillis() / 1000), SECRET, API_KEY);
    }

    protected HttpHeaders signedHeaders(String method, String path, String idempotencyKey,
                                        String body, String timestamp, String secret,
                                        String apiKey) {
        byte[] rawBody = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String canonical = signer.canonicalString(method, path, timestamp, idempotencyKey, rawBody);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Key", apiKey);
        headers.set("X-Timestamp", timestamp);
        headers.set("X-Signature", signer.sign(secret, canonical));
        headers.set("X-Signature-Version", "v1");
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    protected ResponseEntity<String> postPayment(String idempotencyKey, String body) {
        return rest.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, signedHeaders("POST", "/v1/payments", idempotencyKey, body)),
                String.class);
    }

    protected String createBody(String orderCode, long amount) {
        return createBody(orderCode, amount, RETURN_URL);
    }

    protected String createBody(String orderCode, long amount, String returnUrl) {
        return """
               {"order_code":"%s","amount":%d,"currency":"VND","return_url":"%s",\
               "description":"test"}""".formatted(orderCode, amount, returnUrl);
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected String localCheckoutUrl(String createdResponseBody) {
        String checkoutUrl = HmacAuthenticationTest.extract(createdResponseBody, "checkout_url");
        String token = checkoutUrl.substring(checkoutUrl.lastIndexOf('/') + 1);
        return url("/checkout/" + token);
    }
}
