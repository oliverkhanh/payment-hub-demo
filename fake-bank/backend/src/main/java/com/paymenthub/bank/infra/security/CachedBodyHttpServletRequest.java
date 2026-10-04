package com.paymenthub.bank.infra.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final byte[] body;
    private final Map<String, String[]> formParameters;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
        this.formParameters = isFormPost(request) ? parseParameters(request) : null;
    }

    public byte[] getBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished()  { return buffer.available() == 0; }
            @Override public boolean isReady()     { return true; }
            @Override public void setReadListener(ReadListener listener) { }
            @Override public int read()            { return buffer.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public String getParameter(String name) {
        if (formParameters == null) {
            return super.getParameter(name);
        }
        String[] values = formParameters.get(name);
        return values == null ? null : values[0];
    }

    @Override
    public String[] getParameterValues(String name) {
        if (formParameters == null) {
            return super.getParameterValues(name);
        }
        return formParameters.get(name);
    }

    @Override
    public Enumeration<String> getParameterNames() {
        if (formParameters == null) {
            return super.getParameterNames();
        }
        return Collections.enumeration(formParameters.keySet());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (formParameters == null) {
            return super.getParameterMap();
        }
        return Collections.unmodifiableMap(formParameters);
    }

    private boolean isFormPost(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith(FORM_CONTENT_TYPE);
    }

    private Map<String, String[]> parseParameters(HttpServletRequest request) {
        Map<String, List<String>> collected = new LinkedHashMap<>();
        addEncodedPairs(collected, request.getQueryString());
        addEncodedPairs(collected, new String(body, StandardCharsets.UTF_8));

        Map<String, String[]> parameters = new LinkedHashMap<>();
        collected.forEach((name, values) -> parameters.put(name, values.toArray(String[]::new)));
        return parameters;
    }

    private void addEncodedPairs(Map<String, List<String>> target, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return;
        }
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            target.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
    }
}
