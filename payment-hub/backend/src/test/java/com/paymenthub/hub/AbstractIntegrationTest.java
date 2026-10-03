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

/**
 * Integration test trên Postgres THẬT (CLAUDE.md §5: không mock cái Testcontainers chạy được).
 * Cần DB thật vì những thứ đang test nằm Ở TRONG DB: partial unique index, UNIQUE
 * (merchant_id, idempotency_key), và hành vi abort transaction của PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    /** Khớp với seed trong V2__payments.sql. */
    protected static final String API_KEY = "mk_dev_demo";
    protected static final String SECRET = "sk_dev_demo_secret_do_not_use_in_prod";
    protected static final String RETURN_URL = "https://shop.example.com/orders/result";

    /**
     * SINGLETON CONTAINER — cố ý KHÔNG dùng @Container/@Testcontainers.
     *
     * Với @Container, extension dựng container MỚI cho từng test class, nhưng Spring
     * context thì được CACHE dùng lại -> Flyway không chạy lại -> DB rỗng -> không tìm
     * thấy merchant -> mọi request trả 401. Tự start một lần cho cả JVM thì hết lệch.
     * (Container tự tắt khi JVM dừng, Ryuk dọn nốt.)
     */
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
        // Tắt job nền để nó không can thiệp vào test; test nào cần thì gọi job trực tiếp.
        registry.add("hub.jobs.expiry.fixed-delay-ms", () -> "3600000");
        registry.add("hub.jobs.idempotency-cleanup.fixed-delay-ms", () -> "3600000");
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected HmacSigner signer;

    // ─────────────────────────────────────────────────────────────────────────
    // Ký request đúng như một merchant thật sẽ làm.
    // Chuỗi ký: METHOD \n PATH \n TIMESTAMP \n IDEMPOTENCY_KEY \n SHA256(BODY)
    // ─────────────────────────────────────────────────────────────────────────
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
        // snake_case — đúng contract (xem spring.jackson.property-naming-strategy)
        return """
               {"order_code":"%s","amount":%d,"currency":"VND","return_url":"%s",\
               "description":"test"}""".formatted(orderCode, amount, returnUrl);
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * checkout_url trả về mang host/port của hub.checkout.base-url (cấu hình cho môi trường
     * thật), còn test server chạy ở port random -> phải dựng lại URL cục bộ từ token.
     * Merchant thật thì dùng checkout_url NGUYÊN XI; đây là ràng buộc của test, không phải
     * gợi ý rằng merchant được phép tự ghép URL.
     */
    protected String localCheckoutUrl(String createdResponseBody) {
        String checkoutUrl = HmacAuthenticationTest.extract(createdResponseBody, "checkout_url");
        String token = checkoutUrl.substring(checkoutUrl.lastIndexOf('/') + 1);
        return url("/checkout/" + token);
    }
}
