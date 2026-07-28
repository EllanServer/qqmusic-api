package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QQMusicHttp {
    private final int timeoutMillis;

    QQMusicHttp(int timeoutSeconds) {
        this.timeoutMillis = Math.max(5, timeoutSeconds) * 1000;
    }

    Response get(String url, Map<String, String> headers) throws IOException {
        return execute("GET", url, null, null, headers, false);
    }

    Response postJson(String url, JsonObject body, Map<String, String> headers) throws IOException {
        byte[] payload = AllMusic.gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        return execute("POST", url, payload, "application/json; charset=UTF-8", headers, false);
    }

    Response postForm(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(QQMusicSupport.encode(entry.getKey()))
                    .append('=')
                    .append(QQMusicSupport.encode(entry.getValue()));
        }
        byte[] payload = encoded.toString().getBytes(StandardCharsets.UTF_8);
        return execute("POST", url, payload, "application/x-www-form-urlencoded; charset=UTF-8", headers, false);
    }

    private Response execute(String method, String url, byte[] payload, String contentType,
                             Map<String, String> headers, boolean followRedirects) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(followRedirects);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("User-Agent", QQMusicSupport.USER_AGENT);
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (!QQMusicSupport.isBlank(header.getKey()) && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                if (!QQMusicSupport.isBlank(contentType)) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            return new Response(status, QQMusicSupport.readBytes(stream), connection.getHeaderFields());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (pairs == null) {
            return headers;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            headers.put(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    static final class Response {
        final int status;
        final byte[] body;
        final Map<String, List<String>> headers;

        Response(int status, byte[] body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
            this.headers = headers == null ? Collections.<String, List<String>>emptyMap() : headers;
        }

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        boolean isRedirect() {
            return status >= 300 && status < 400;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonObject json() throws IOException {
            try {
                JsonObject object = AllMusic.gson.fromJson(text(), JsonObject.class);
                return object == null ? new JsonObject() : object;
            } catch (RuntimeException e) {
                throw new IOException("QQ Music returned invalid JSON", e);
            }
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && name.equalsIgnoreCase(entry.getKey())
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return "";
        }
    }

    static final class CookieJar {
        private static final Pattern SET_COOKIE_PAIR = Pattern.compile(
                "(?:^|,\\s*)([!#$%&'*+.^_`|~0-9A-Za-z-]+)=([^;,\\r\\n]*)"
        );
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();

        static CookieJar parse(String header) {
            CookieJar jar = new CookieJar();
            if (QQMusicSupport.isBlank(header)) {
                return jar;
            }
            String[] parts = header.split(";");
            for (String part : parts) {
                int separator = part.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                jar.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
            }
            return jar;
        }

        void mergeSetCookies(Map<String, List<String>> headers) {
            if (headers == null) {
                return;
            }
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())
                        || header.getValue() == null) {
                    continue;
                }
                for (String value : header.getValue()) {
                    Matcher matcher = SET_COOKIE_PAIR.matcher(value == null ? "" : value);
                    while (matcher.find()) {
                        put(matcher.group(1), matcher.group(2));
                    }
                }
            }
        }

        void put(String key, String value) {
            if (QQMusicSupport.isBlank(key) || isAttribute(key)) {
                return;
            }
            String existing = findKey(key);
            String incoming = value == null ? "" : value;
            // Set-Cookie can clear the same name for a different domain after
            // setting the usable graph.qq.com ticket. This short-lived jar is
            // domainless, so retain the non-empty value instead of losing it.
            if (existing != null && QQMusicSupport.isBlank(incoming)
                    && !QQMusicSupport.isBlank(values.get(existing))) {
                return;
            }
            if (existing != null && !existing.equals(key)) {
                values.remove(existing);
            }
            values.put(key, incoming);
        }

        String get(String key) {
            String existing = findKey(key);
            return existing == null ? "" : QQMusicSupport.trim(values.get(existing));
        }

        void remove(String key) {
            String existing = findKey(key);
            if (existing != null) {
                values.remove(existing);
            }
        }

        Set<String> names() {
            return new LinkedHashSet<>(values.keySet());
        }

        String header() {
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!QQMusicSupport.isBlank(entry.getKey()) && !QQMusicSupport.isBlank(entry.getValue())
                        && !isAttribute(entry.getKey())) {
                    pairs.add(entry.getKey() + "=" + entry.getValue());
                }
            }
            return String.join("; ", pairs);
        }

        private String findKey(String key) {
            if (key == null) {
                return null;
            }
            for (String candidate : values.keySet()) {
                if (key.equalsIgnoreCase(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private static boolean isAttribute(String key) {
            String normalized = key.toLowerCase(Locale.ROOT);
            return "path".equals(normalized)
                    || "domain".equals(normalized)
                    || "expires".equals(normalized)
                    || "max-age".equals(normalized)
                    || "samesite".equals(normalized)
                    || "secure".equals(normalized)
                    || "httponly".equals(normalized);
        }
    }
}
