package ru.abs7.leadprosvet.service.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.leadprosvet.config.AppProperties;
import ru.abs7.leadprosvet.domain.BitrixPortal;
import ru.abs7.leadprosvet.repository.BitrixPortalRepository;
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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class BitrixRestClient {

    private static final Logger log = LoggerFactory.getLogger(BitrixRestClient.class);

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final BitrixPortalRepository bitrixPortalRepository;
    private final HttpClient httpClient;

    public BitrixRestClient(
            ObjectMapper objectMapper,
            AppProperties appProperties,
            BitrixPortalRepository bitrixPortalRepository
    ) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.bitrixPortalRepository = bitrixPortalRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> call(BitrixPortal portal, String method) {
        return call(portal, method, Map.of());
    }

    public Map<String, Object> call(BitrixPortal portal, String method, Map<String, ?> params) {
        validateRestCall(portal, method);

        try {
            return callOnce(portal, method, params, portal.getAccessToken());
        } catch (BitrixRestException firstError) {
            if (!isExpiredTokenError(firstError)) {
                throw firstError;
            }

            log.info("Bitrix access token expired for portal {}, refreshing token and retrying {}", portal.getDomain(), method);
            refreshAccessToken(portal);
            return callOnce(portal, method, params, portal.getAccessToken());
        }
    }

    public Object result(BitrixPortal portal, String method) {
        return call(portal, method).get("result");
    }

    public Map<String, Object> refreshAccessToken(BitrixPortal portal) {
        if (portal == null) {
            throw new BitrixRestException("Bitrix portal is not selected");
        }
        if (isBlank(portal.getRefreshToken())) {
            throw new BitrixRestException("Bitrix refresh token is empty. Open the app inside Bitrix24 or reinstall it.");
        }
        if (isBlank(appProperties.bitrixClientId()) || isBlank(appProperties.bitrixClientSecret())) {
            throw new BitrixRestException("Bitrix access token expired, but BITRIX_CLIENT_ID / BITRIX_CLIENT_SECRET are not configured. Open the app inside Bitrix24 once to save a fresh AUTH_ID/REFRESH_ID, or add these two env variables from local app settings.");
        }

        Map<String, Object> bodyParams = new LinkedHashMap<>();
        bodyParams.put("grant_type", "refresh_token");
        bodyParams.put("client_id", appProperties.bitrixClientId());
        bodyParams.put("client_secret", appProperties.bitrixClientSecret());
        bodyParams.put("refresh_token", portal.getRefreshToken());

        String primaryTokenUrl = tokenUrl(portal);
        try {
            return refreshAccessTokenViaUrl(portal, primaryTokenUrl, bodyParams);
        } catch (BitrixRestException primaryError) {
            String fallback = "https://oauth.bitrix.info/oauth/token/";
            if (primaryTokenUrl.equals(fallback)) {
                throw primaryError;
            }
            log.warn("Bitrix token refresh failed via {}, trying fallback {}: {}", primaryTokenUrl, fallback, primaryError.getMessage());
            return refreshAccessTokenViaUrl(portal, fallback, bodyParams);
        }
    }

    private Map<String, Object> refreshAccessTokenViaUrl(
            BitrixPortal portal,
            String tokenUrl,
            Map<String, Object> bodyParams
    ) {
        String encodedBody = formEncode(bodyParams);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodedBody, StandardCharsets.UTF_8))
                .build();

        try {
            log.info("==================== BITRIX TOKEN REFRESH REQUEST START ====================");
            log.info("Bitrix token refresh URL: {}", tokenUrl);
            log.info("Bitrix token refresh body SAFE FULL:\n{}", maskSecrets(encodedBody));

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body() == null ? "" : response.body();
            log.info("==================== BITRIX TOKEN REFRESH RESPONSE START ====================");
            log.info("Bitrix token refresh HTTP status: {}", response.statusCode());
            log.info("Bitrix token refresh response SAFE FULL:\n{}", maskSecrets(responseBody));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BitrixRestException("Bitrix token refresh HTTP " + response.statusCode() + ": " + trim(responseBody, 900));
            }

            Map<String, Object> payload = parseJsonObject(responseBody);
            Object error = payload.get("error");
            if (error != null && !String.valueOf(error).isBlank()) {
                Object description = payload.get("error_description");
                throw new BitrixRestException("Bitrix token refresh error: " + error + " " + Objects.toString(description, ""));
            }

            String accessToken = stringValue(payload.get("access_token"));
            String refreshToken = stringValue(payload.get("refresh_token"));
            if (isBlank(accessToken)) {
                throw new BitrixRestException("Bitrix token refresh response has no access_token: " + trim(responseBody, 900));
            }

            portal.setAccessToken(accessToken);
            if (!isBlank(refreshToken)) {
                portal.setRefreshToken(refreshToken);
            }
            String clientEndpoint = stringValue(payload.get("client_endpoint"));
            String serverEndpoint = stringValue(payload.get("server_endpoint"));
            String memberId = stringValue(payload.get("member_id"));
            if (!isBlank(clientEndpoint)) {
                portal.setClientEndpoint(clientEndpoint);
            }
            if (!isBlank(serverEndpoint)) {
                portal.setServerEndpoint(serverEndpoint);
            }
            if (!isBlank(memberId)) {
                portal.setMemberId(memberId);
            }
            portal.setUpdatedAt(OffsetDateTime.now());
            bitrixPortalRepository.save(portal);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("tokenEndpoint", tokenUrl);
            result.put("hasAccessToken", true);
            result.put("hasRefreshToken", !isBlank(portal.getRefreshToken()));
            result.put("clientEndpoint", value(portal.getClientEndpoint()));
            result.put("serverEndpoint", value(portal.getServerEndpoint()));
            result.put("memberId", value(portal.getMemberId()));
            result.put("expiresIn", payload.get("expires_in"));
            result.put("expires", payload.get("expires"));
            return result;
        } catch (IOException e) {
            throw new BitrixRestException("Bitrix token refresh IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BitrixRestException("Bitrix token refresh request interrupted", e);
        }
    }

    private Map<String, Object> callOnce(BitrixPortal portal, String method, Map<String, ?> params, String accessToken) {
        String url = methodUrl(portal, method);
        Map<String, Object> bodyParams = new LinkedHashMap<>();
        if (params != null) {
            bodyParams.putAll(params);
        }
        bodyParams.put("auth", accessToken);

        String encodedBody = formEncode(bodyParams);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodedBody, StandardCharsets.UTF_8))
                .build();

        try {
            log.info("==================== BITRIX REST REQUEST START ====================");
            log.info("Bitrix REST method: {}", method);
            log.info("Bitrix REST URL: {}", url);
            log.info("Bitrix REST portal: {}", portal.getDomain());
            log.info("Bitrix REST request body SAFE FULL:\n{}", maskSecrets(encodedBody));

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body() == null ? "" : response.body();
            log.info("==================== BITRIX REST RESPONSE START ====================");
            log.info("Bitrix REST method: {}", method);
            log.info("Bitrix REST HTTP status: {}", response.statusCode());
            log.info("Bitrix REST response body SAFE FULL:\n{}", maskSecrets(responseBody));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BitrixRestException("Bitrix REST HTTP " + response.statusCode() + " for " + method + ": " + trim(responseBody, 900));
            }
            Map<String, Object> payload = parseJsonObject(responseBody);
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

    private void validateRestCall(BitrixPortal portal, String method) {
        if (portal == null) {
            throw new BitrixRestException("Bitrix portal is not selected");
        }
        if (isBlank(portal.getAccessToken())) {
            throw new BitrixRestException("Bitrix access token is empty. Reinstall the local app in Bitrix24.");
        }
        if (isBlank(method)) {
            throw new BitrixRestException("Bitrix REST method is empty");
        }
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

    private String tokenUrl(BitrixPortal portal) {
        if (!isBlank(appProperties.bitrixTokenEndpoint())) {
            return appProperties.bitrixTokenEndpoint().trim();
        }
        String serverEndpoint = portal.getServerEndpoint();
        if (!isBlank(serverEndpoint) && serverEndpoint.startsWith("http")) {
            String value = serverEndpoint.trim();
            if (value.endsWith("/rest/")) {
                return value.substring(0, value.length() - "/rest/".length()) + "/oauth/token/";
            }
            if (value.endsWith("/rest")) {
                return value.substring(0, value.length() - "/rest".length()) + "/oauth/token/";
            }
        }
        return "https://oauth.bitrix.info/oauth/token/";
    }

    private String formEncode(Map<String, ?> params) {
        StringBuilder result = new StringBuilder();
        params.forEach((key, value) -> appendParam(result, key, value));
        return result.toString();
    }

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

    private boolean isExpiredTokenError(BitrixRestException error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }
        String message = error.getMessage().toLowerCase();
        return message.contains("expired_token") || message.contains("token has expired");
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Object value(Object value) {
        return value == null ? "" : value;
    }

    private String maskSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?i)(access_token=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(refresh_token=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(client_secret=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(auth=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(application_token=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(\\\"access_token\\\"\\s*:\\s*\\\")[^\\\"]+", "$1***")
                .replaceAll("(?i)(\\\"refresh_token\\\"\\s*:\\s*\\\")[^\\\"]+", "$1***")
                .replaceAll("(?i)(\\\"client_secret\\\"\\s*:\\s*\\\")[^\\\"]+", "$1***");
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
