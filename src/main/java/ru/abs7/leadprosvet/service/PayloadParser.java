package ru.abs7.leadprosvet.service;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PayloadParser {

    public Map<String, String> mergeParamsAndBody(Map<String, String> params, String body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (params != null) {
            result.putAll(params);
        }
        if (body != null && !body.isBlank() && body.contains("=")) {
            parseUrlEncoded(body).forEach(result::putIfAbsent);
        }
        return result;
    }

    public Map<String, String> parseUrlEncoded(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = decode(pair.substring(0, idx));
            String value = decode(pair.substring(idx + 1));
            result.put(key, value);
        }
        return result;
    }

    public String getAny(Map<String, String> data, String... keys) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = data.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String decode(String value) {
        return UriUtils.decode(value.replace("+", "%20"), StandardCharsets.UTF_8);
    }
}
