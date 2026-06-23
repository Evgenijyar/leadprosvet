package ru.abs7.leadprosvet.service.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.leadprosvet.domain.BitrixPortal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class BitrixRestClient {

    private static final Logger log = LoggerFactory.getLogger(BitrixRestClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BitrixRestClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> call(BitrixPortal portal, String method) {
        return call(portal, method, Map.of());
    }

    public Map<String, Object> call(BitrixPortal portal, String method, Map<String, ?> params) {
        if (portal == null) {
            throw new BitrixRestException("Bitrix portal is not selected");
        }
        if (isBlank(portal.getAccessToken())) {
            throw new BitrixRestException("Bitrix access token is empty. Reinstall the local app in Bitrix24.");
        }
        if (isBlank(method)) {
            throw new BitrixRestException("Bitrix REST method is empty");
        }

        String url = methodUrl(portal, method);
        Map<String, Object> bodyParams = new LinkedHashMap<>();
        if (params != null) {
            bodyParams.putAll(params);
        }
        bodyParams.put("auth", portal.getAccessToken());

        String encodedBody = formEncode(bodyParams);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodedBody, StandardCharsets.UTF_8))
                .build();

        try {
            log.info("Calling Bitrix REST method {} for portal {}", method, portal.getDomain());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BitrixRestException("Bitrix REST HTTP " + response.statusCode() + " for " + method + ": " + trim(response.body(), 900));
            }
            Map<String, Object> payload = parseJsonObject(response.body());
            Object error = payload.get("error");
            if (error != null && !String.valueOf(error).isBlank()) {
                Object description = payload.get("error_description");
                throw new BitrixRestException("Bitrix REST error for " + method + ": " + error + " " + Objects.toString(description, ""));
            }
            return payload;
        } catch (IOException e) {
            throw new BitrixRestException("Bitrix REST IO error for " + method + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BitrixRestException("Bitrix REST request interrupted for " + method, e);
        }
    }

    public Object result(BitrixPortal portal, String method) {
        return call(portal, method).get("result");
    }

    private String methodUrl(BitrixPortal portal, String method) {
        String base = portal.getClientEndpoint();
        if (isBlank(base)) {
            String domain = portal.getDomain();
            if (isBlank(domain)) {
                throw new BitrixRestException("Bitrix portal has no domain/client endpoint");
            }
            base = "https://" + normalizeDomain(domain) + "/rest/";
        }

        String normalizedBase = base.trim();
        if (!normalizedBase.endsWith("/")) {
            normalizedBase += "/";
        }
        return normalizedBase + method + ".json";
    }

    private String formEncode(Map<String, ?> params) {
        StringBuilder result = new StringBuilder();
        params.forEach((key, value) -> appendParam(result, key, value));
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendParam(StringBuilder result, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((nestedKey, nestedValue) -> appendParam(result, key + "[" + nestedKey + "]", nestedValue));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object item : iterable) {
                appendParam(result, key + "[" + i + "]", item);
                i++;
            }
            return;
        }
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            for (int i = 0; i < array.length; i++) {
                appendParam(result, key + "[" + i + "]", array[i]);
            }
            return;
        }
        if (!result.isEmpty()) {
            result.append('&');
        }
        result.append(urlEncode(key)).append('=').append(urlEncode(String.valueOf(value)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String body) {
        try {
            Object value = objectMapper.readValue(body, Object.class);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new BitrixRestException("Bitrix REST response is not a JSON object: " + trim(body, 500));
        } catch (JacksonException e) {
            throw new BitrixRestException("Cannot parse Bitrix REST JSON response: " + trim(body, 500), e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizeDomain(String raw) {
        String value = raw.trim().replace("https://", "").replace("http://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        return value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
