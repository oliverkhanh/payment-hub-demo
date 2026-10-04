package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class BankPayloads {

    private static final TypeReference<LinkedHashMap<String, String>> FORM_MAP =
            new TypeReference<>() { };

    private BankPayloads() { }

    public static byte[] toJsonBytes(ObjectMapper objectMapper, Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được body gửi sang bank", e);
        }
    }

    public static byte[] toFormBytes(ObjectMapper objectMapper, Object payload) {
        Map<String, String> form = objectMapper.convertValue(payload, FORM_MAP);
        String encoded = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(
                        e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T read(ObjectMapper objectMapper, String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception unreadable) {
            return null;
        }
    }
}
