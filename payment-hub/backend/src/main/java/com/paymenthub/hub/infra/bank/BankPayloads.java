package com.paymenthub.hub.infra.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tiện ích đóng gói/mở gói body. Dùng Map (không dùng DTO) là CÓ Ý:
 * `spring.jackson.property-naming-strategy: SNAKE_CASE` chỉ áp cho property của POJO,
 * KHÔNG áp cho khoá của Map. Nhờ vậy TCB giữ được camelCase thật của nó mà không phải
 * dựng riêng một ObjectMapper thứ hai.
 */
final class BankPayloads {

    private BankPayloads() { }

    /** Byte sẽ được KÝ và cũng chính là byte được GỬI — không serialize hai lần. */
    static byte[] toJsonBytes(ObjectMapper json, Map<String, Object> payload) {
        try {
            return json.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được body gửi sang bank", e);
        }
    }

    /** form-urlencoded: PHẢI encode giá trị, nếu không một dấu '&' trong tên merchant là hỏng body. */
    static byte[] toFormBytes(Map<String, String> form) {
        String encoded = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(
                        e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    /** @return null nếu không đọc được — caller phải coi đó là KHÔNG BIẾT, không phải từ chối. */
    static JsonNode parse(ObjectMapper json, String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return json.readTree(body);
        } catch (Exception notJson) {
            return null;
        }
    }
}
