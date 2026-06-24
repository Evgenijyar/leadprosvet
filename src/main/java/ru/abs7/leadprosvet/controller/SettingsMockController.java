package ru.abs7.leadprosvet.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.leadprosvet.dto.StoredSettingResponse;
import ru.abs7.leadprosvet.service.JsonStorageService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsMockController {

    private static final Logger log = LoggerFactory.getLogger(SettingsMockController.class);
    private static final String SETTINGS_KEY = "leadprosvet.settings";

    private final JsonStorageService jsonStorageService;

    public SettingsMockController(JsonStorageService jsonStorageService) {
        this.jsonStorageService = jsonStorageService;
    }

    @GetMapping("/contact-fields")
    public ResponseEntity<List<Map<String, String>>> contactFields() {
        return ResponseEntity.ok(List.of(
                field("NAME", "Имя", "Контакт"),
                field("LAST_NAME", "Фамилия", "Контакт"),
                field("COMPANY_TITLE", "Компания", "Лид / Контакт"),
                field("PHONE", "Телефон", "Контакт"),
                field("EMAIL", "Email", "Контакт"),
                field("WEB", "Сайт", "Контакт"),
                field("ADDRESS", "Адрес", "Контакт"),
                field("UF_CRM_INN", "ИНН", "Пользовательское поле"),
                field("UF_CRM_ACTIVITY", "Сфера деятельности", "Пользовательское поле"),
                field("COMMENTS", "Комментарии", "Лид"),
                field("SOURCE_DESCRIPTION", "Описание источника", "Лид")
        ));
    }

    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> current() {
        return ResponseEntity.ok(normalizeSettings(jsonStorageService.getJsonSetting(SETTINGS_KEY), new LinkedHashMap<>()));
    }

    @PostMapping("/save")
    public ResponseEntity<StoredSettingResponse> save(@RequestBody Map<String, Object> payload) {
        Map<String, Object> existing = jsonStorageService.getJsonSetting(SETTINGS_KEY);
        Map<String, Object> normalized = normalizeSettings(existing, payload);
        normalized.putIfAbsent("serviceEnabled", true);

        log.info("Settings saved to file-based H2: provider={}, hasOpenAiProfile={}, hasGoogleProfile={}",
                normalized.get("provider"),
                nestedMap(normalized.get("llmProfiles")).containsKey("openai"),
                nestedMap(normalized.get("llmProfiles")).containsKey("google"));

        OffsetDateTime savedAt = jsonStorageService.saveJsonSetting(SETTINGS_KEY, normalized);
        return ResponseEntity.ok(new StoredSettingResponse(true, SETTINGS_KEY, savedAt));
    }

    @PostMapping("/service-enabled")
    public ResponseEntity<StoredSettingResponse> serviceEnabled(@RequestBody Map<String, Object> payload) {
        boolean enabled = booleanValue(payload.get("enabled"), true);
        Map<String, Object> settings = normalizeSettings(jsonStorageService.getJsonSetting(SETTINGS_KEY), new LinkedHashMap<>());
        settings.put("serviceEnabled", enabled);
        log.info("LeadProsvet service enabled changed: enabled={}", enabled);
        OffsetDateTime savedAt = jsonStorageService.saveJsonSetting(SETTINGS_KEY, settings);
        return ResponseEntity.ok(new StoredSettingResponse(true, SETTINGS_KEY, savedAt));
    }

    private Map<String, Object> normalizeSettings(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (existing != null) {
            result.putAll(existing);
        }
        if (incoming != null) {
            result.putAll(incoming);
        }

        String provider = normalizeProvider(firstNonBlank(
                stringValue(result.get("provider")),
                stringValue(nestedMap(result.get("llm")).get("provider")),
                "openai"
        ));
        result.put("provider", provider);

        Map<String, Object> profiles = new LinkedHashMap<>();
        mergeProfiles(profiles, nestedMap(existing == null ? null : existing.get("llmProfiles")));
        mergeProfiles(profiles, nestedMap(existing == null ? null : existing.get("llmByProvider")));
        mergeSingleProfile(profiles, nestedMap(existing == null ? null : existing.get("llm")),
                normalizeProvider(firstNonBlank(
                        stringValue(nestedMap(existing == null ? null : existing.get("llm")).get("provider")),
                        stringValue(existing == null ? null : existing.get("provider")),
                        provider
                )));

        mergeProfiles(profiles, nestedMap(incoming == null ? null : incoming.get("llmProfiles")));
        mergeProfiles(profiles, nestedMap(incoming == null ? null : incoming.get("llmByProvider")));
        mergeSingleProfile(profiles, nestedMap(incoming == null ? null : incoming.get("llm")), provider);

        profiles.put("openai", normalizeProfile("openai", nestedMap(profiles.get("openai"))));
        profiles.put("google", normalizeProfile("google", nestedMap(profiles.get("google"))));

        result.put("llmProfiles", profiles);
        result.put("llm", profiles.get(provider)); // compatibility with old code / old UI
        return result;
    }

    private void mergeProfiles(Map<String, Object> target, Map<String, Object> source) {
        for (String provider : List.of("openai", "google")) {
            Map<String, Object> incomingProfile = nestedMap(source.get(provider));
            if (!incomingProfile.isEmpty()) {
                mergeSingleProfile(target, incomingProfile, provider);
            }
        }
    }

    private void mergeSingleProfile(Map<String, Object> profiles, Map<String, Object> incomingProfile, String provider) {
        if (incomingProfile == null || incomingProfile.isEmpty()) {
            return;
        }
        String normalizedProvider = normalizeProvider(firstNonBlank(stringValue(incomingProfile.get("provider")), provider));
        Map<String, Object> current = new LinkedHashMap<>(normalizeProfile(normalizedProvider, nestedMap(profiles.get(normalizedProvider))));

        putIfPresent(current, "endpointUrl", incomingProfile.get("endpointUrl"));
        putIfPresent(current, "modelId", incomingProfile.get("modelId"));

        // API keys are sensitive and annoying to re-enter. Old UI could send empty `apiKey`,
        // so we keep previous keys unless the new multi-key `apiKeys` field is explicitly sent.
        boolean apiKeysExplicitlySent = incomingProfile.containsKey("apiKeys");
        List<String> incomingKeys = apiKeysFromProfile(incomingProfile);
        if (apiKeysExplicitlySent) {
            current.put("apiKeys", incomingKeys);
            current.put("apiKey", incomingKeys.isEmpty() ? "" : incomingKeys.getFirst()); // compatibility with old code/UI
        } else if (!incomingKeys.isEmpty()) {
            current.put("apiKeys", incomingKeys);
            current.put("apiKey", incomingKeys.getFirst()); // compatibility with old code/UI
        }
        List<String> savedKeys = apiKeysFromProfile(current);
        current.put("apiKeys", savedKeys);
        current.put("apiKey", savedKeys.isEmpty() ? "" : savedKeys.getFirst());
        current.put("apiKeyPresent", !savedKeys.isEmpty());
        current.put("apiKeyCount", savedKeys.size());
        current.put("provider", normalizedProvider);
        profiles.put(normalizedProvider, current);
    }

    private Map<String, Object> normalizeProfile(String provider, Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("endpointUrl", firstNonBlank(
                stringValue(profile.get("endpointUrl")),
                provider.equals("google")
                        ? "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
                        : "https://api.openai.com/v1/chat/completions"
        ));
        result.put("modelId", firstNonBlank(
                stringValue(profile.get("modelId")),
                provider.equals("google") ? "gemini-2.5-flash" : "gpt-4.1-mini"
        ));
        List<String> apiKeys = apiKeysFromProfile(profile);
        result.put("apiKeys", apiKeys);
        result.put("apiKey", apiKeys.isEmpty() ? "" : apiKeys.getFirst());
        result.put("apiKeyPresent", !apiKeys.isEmpty());
        result.put("apiKeyCount", apiKeys.size());
        return result;
    }

    private List<String> apiKeysFromProfile(Map<String, Object> profile) {
        List<String> result = new ArrayList<>();
        Object apiKeys = profile == null ? null : profile.get("apiKeys");
        if (apiKeys instanceof List<?> list) {
            for (Object item : list) {
                String key = stringValue(item);
                if (key != null && !key.isBlank()) {
                    result.add(key.trim());
                }
            }
        }
        String singleKey = stringValue(profile == null ? null : profile.get("apiKey"));
        if (result.isEmpty() && singleKey != null && !singleKey.isBlank()) {
            result.add(singleKey.trim());
        }
        return result;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        String text = stringValue(value);
        if (text != null && !text.isBlank()) {
            target.put(key, text);
        }
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
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

    private String normalizeProvider(String value) {
        return "google".equalsIgnoreCase(value == null ? "" : value.trim()) ? "google" : "openai";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, String> field(String id, String label, String group) {
        return Map.of("id", id, "label", label, "group", group);
    }
}
