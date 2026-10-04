package com.paymenthub.hub.infra.bank;

import com.paymenthub.hub.config.HubBankProperties;
import com.paymenthub.hub.config.HubBankProperties.BankInfo;
import com.paymenthub.hub.infra.security.HmacSigner;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BankCircuitBreakerTest {

    private static final String BANK = "VCB";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private final HmacSigner hmacSigner = new HmacSigner();
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .build());

    private HttpServer stub;

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("Bank chết liên tục -> mở mạch, cú gọi sau KHÔNG đi ra mạng nữa")
    void bank_chet_thi_mo_mach() {
        BankHttpClient client = clientFor("http://127.0.0.1:1");

        BankHttpClient.Response lanMot = post(client, "http://127.0.0.1:1/x");
        BankHttpClient.Response lanHai = post(client, "http://127.0.0.1:1/x");

        assertThat(lanMot.notSent()).isTrue();
        assertThat(lanMot.failureReason()).isNotEqualTo("circuit_open");
        assertThat(lanHai.notSent()).isTrue();

        assertThat(registry.circuitBreaker(BANK).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        BankHttpClient.Response lanBa = post(client, "http://127.0.0.1:1/x");
        assertThat(lanBa.notSent()).isTrue();
        assertThat(lanBa.failureReason()).isEqualTo("circuit_open");
    }

    @Test
    @DisplayName("Bank TỪ CHỐI (4xx) không phải bank HỎNG — không được mở mạch")
    void bank_tu_choi_thi_khong_mo_mach() throws IOException {
        String baseUrl = startStub(400);
        BankHttpClient client = clientFor(baseUrl);

        for (int lan = 0; lan < 5; lan++) {
            assertThat(post(client, baseUrl + "/x").status()).isEqualTo(400);
        }

        assertThat(registry.circuitBreaker(BANK).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Bank trả 5xx liên tục -> mở mạch")
    void bank_tra_5xx_thi_mo_mach() throws IOException {
        String baseUrl = startStub(503);
        BankHttpClient client = clientFor(baseUrl);

        post(client, baseUrl + "/x");
        post(client, baseUrl + "/x");

        assertThat(registry.circuitBreaker(BANK).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    private BankHttpClient.Response post(BankHttpClient client, String url) {
        return client.post(BANK, url, MediaType.APPLICATION_JSON, BODY, "idem-1");
    }

    private BankHttpClient clientFor(String baseUrl) {
        BankInfo bank = new BankInfo("Vietcombank", true, 1, baseUrl,
                "hub_key_vcb_dev", "secret", 300, 800, 0);
        HubBankProperties props = new HubBankProperties(Map.of(BANK, bank));

        return new BankHttpClient(props, hmacSigner, registry);
    }

    private String startStub(int statusCode) throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/", exchange -> {
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stub.start();

        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }
}
