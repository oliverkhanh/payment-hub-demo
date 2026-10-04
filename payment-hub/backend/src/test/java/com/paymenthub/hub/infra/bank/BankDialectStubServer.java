package com.paymenthub.hub.infra.bank;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class BankDialectStubServer implements AutoCloseable {

    private final HttpServer server;
    final AtomicInteger callCount = new AtomicInteger();
    volatile byte[] body;
    volatile Map<String, String> headers;
    volatile String path;
    volatile String contentType;
    volatile int responseStatus = 201;
    volatile String responseBody = "{}";
    volatile long responseDelayMs = 0;

    BankDialectStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        callCount.incrementAndGet();
        path = exchange.getRequestURI().getRawPath();
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        Map<String, String> captured = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> captured.put(k, v.get(0)));
        headers = captured;
        contentType = captured.get("Content-type");

        if (responseDelayMs > 0) {
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
