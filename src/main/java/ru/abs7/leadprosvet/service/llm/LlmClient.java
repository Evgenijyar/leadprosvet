package ru.abs7.leadprosvet.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final ObjectMapper objectMapper;

    public LlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LlmResult generate(Map<String, Object> settings, String prompt) {
        Map<String, Object> llm = nestedMap(settings.get("llm"));
        Map<String, Object> proxy = nestedMap(settings.get("proxy"));

        String provider = firstNonBlank(
                stringValue(llm.get("provider")),
                stringValue(settings.get("provider")),
                "openai"
        ).toLowerCase();
        String model = firstNonBlank(stringValue(llm.get("modelId")), provider.equals("google") ? "gemini-2.5-flash" : "gpt-4.1-mini");
        String endpoint = firstNonBlank(stringValue(llm.get("endpointUrl")), defaultEndpoint(provider));
        String apiKey = stringValue(llm.get("apiKey"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM API key is empty. Сохрани API-ключ на вкладке Настройки LLM.");
        }

        Map<String, Object> requestPayload;
        HttpRequest.Builder requestBuilder;
        String resolvedEndpoint = endpoint.replace("{model}", urlEncode(model));

        if (provider.equals("google")) {
            requestPayload = googlePayload(prompt);
            resolvedEndpoint = withGoogleKey(resolvedEndpoint, apiKey);
            requestBuilder = HttpRequest.newBuilder(URI.create(resolvedEndpoint))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json");
        } else {
            requestPayload = openAiPayload(model, prompt);
            requestBuilder = HttpRequest.newBuilder(URI.create(resolvedEndpoint))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey);
        }

        String requestJson = toPrettyJson(requestPayload);
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        log.info("==================== LLM REQUEST START ====================");
        log.info("LLM provider: {}", provider);
        log.info("LLM endpoint: {}", maskUrl(resolvedEndpoint));
        log.info("LLM model: {}", model);
        log.info("LLM proxy enabled: {}", Boolean.TRUE.equals(proxy.get("enabled")));
        log.info("LLM request headers: Content-Type=application/json; Accept=application/json; Authorization={}", provider.equals("openai") ? "Bearer ***" : "not-used");
        log.info("LLM request body FULL:\n{}", requestJson);
        log.info("==================== LLM REQUEST END ====================");

        try {
            HttpClient client = httpClient(proxy);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body() == null ? "" : response.body();

            log.info("==================== LLM RESPONSE START ====================");
            log.info("LLM HTTP status: {}", response.statusCode());
            log.info("LLM response body FULL:\n{}", responseBody);
            log.info("==================== LLM RESPONSE END ====================");

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LLM HTTP " + response.statusCode() + ": " + responseBody);
            }

            String text = extractText(provider, responseBody);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("LLM response has no generated text: " + responseBody);
            }
            return new LlmResult(provider, model, maskUrl(resolvedEndpoint), requestJson, responseBody, text.trim());
        } catch (IOException e) {
            throw new IllegalStateException("LLM IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM request interrupted", e);
        }
    }

    private HttpClient httpClient(Map<String, Object> proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(40))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (Boolean.TRUE.equals(proxy.get("enabled"))) {
            String host = stringValue(proxy.get("host"));
            int port = intValue(proxy.get("port"), 0);
            if (host != null && !host.isBlank() && port > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(host.trim(), port)));
                String login = stringValue(proxy.get("login"));
                String password = stringValue(proxy.get("password"));
                if (login != null && !login.isBlank()) {
                    builder.authenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(login, password == null ? new char[0] : password.toCharArray());
                        }
                    });
                }
            }
        }
        return builder.build();
    }

    private Map<String, Object> openAiPayload(String model, String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", "Ты помогаешь менеджеру быстро понять лид из CRM. Отвечай по-русски, структурно и по делу."),
                Map.of("role", "user", "content", prompt)
        ));
        return payload;
    }

    private Map<String, Object> googlePayload(String prompt) {
        return Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
    }

    @SuppressWarnings("unchecked")
    private String extractText(String provider, String responseBody) {
        try {
            Object parsed = objectMapper.readValue(responseBody, Object.class);
            if (!(parsed instanceof Map<?, ?> root)) {
                return null;
            }
            if (provider.equals("google")) {
                Object candidates = root.get("candidates");
                if (candidates instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> candidate) {
                    Object content = candidate.get("content");
                    if (content instanceof Map<?, ?> contentMap) {
                        Object parts = contentMap.get("parts");
                        if (parts instanceof List<?> partList && !partList.isEmpty() && partList.getFirst() instanceof Map<?, ?> part) {
                            return stringValue(part.get("text"));
                        }
                    }
                }
            } else {
                Object choices = root.get("choices");
                if (choices instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> choice) {
                    Object message = choice.get("message");
                    if (message instanceof Map<?, ?> messageMap) {
                        return stringValue(messageMap.get("content"));
                    }
                }
            }
            return null;
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot parse LLM response JSON: " + e.getMessage(), e);
        }
    }

    private String defaultEndpoint(String provider) {
        if (provider.equals("google")) {
            return "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
        }
        return "https://api.openai.com/v1/chat/completions";
    }

    private String withGoogleKey(String endpoint, String apiKey) {
        if (endpoint.contains("key=")) {
            return endpoint;
        }
        String separator = endpoint.contains("?") ? "&" : "?";
        return endpoint + separator + "key=" + urlEncode(apiKey);
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize LLM request JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(Objects.toString(value, ""), StandardCharsets.UTF_8);
    }

    private String maskUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)([?&]key=)[^&]+", "$1***");
    }

    public record LlmResult(
            String provider,
            String model,
            String endpoint,
            String requestJson,
            String responseJson,
            String text
    ) {
    }
}
