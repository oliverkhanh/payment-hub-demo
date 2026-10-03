package com.paymenthub.hub.infra.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * getInputStream() là stream MỘT CHIỀU, đọc được MỘT LẦN — nó là ống nước từ socket,
 * không phải mảng byte nằm sẵn. Nên nó vỡ theo CẢ HAI chiều:
 *   - filter đọc trước  -> controller thấy stream rỗng (HttpMessageNotReadableException)
 *   - Jackson đọc trước -> filter không còn gì để băm
 *
 * Wrapper này đọc HẾT vào byte[] ngay trong constructor, rồi phát lại cho ai cũng được.
 * (Khác ContentCachingRequestWrapper của Spring: cái đó chỉ cache SAU KHI có ai đọc.)
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
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
            @Override public void setReadListener(ReadListener listener) { /* blocking IO */ }
            @Override public int read()            { return buffer.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
