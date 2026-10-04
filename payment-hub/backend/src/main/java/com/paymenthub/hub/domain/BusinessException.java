package com.paymenthub.hub.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultDetail());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessException with(String key, Object value) {
        extensions.put(key, value);
        return this;
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, Object> extensions() { return extensions; }
}
