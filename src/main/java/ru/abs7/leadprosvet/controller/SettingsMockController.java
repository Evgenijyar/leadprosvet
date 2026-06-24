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
        return ResponseEntity.ok(jsonStorageService.getJsonSetting(SETTINGS_KEY));
    }

    @PostMapping("/save")
    public ResponseEntity<StoredSettingResponse> save(@RequestBody Map<String, Object> payload) {
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        normalized.putIfAbsent("serviceEnabled", true);
        log.info("Settings saved to file-based H2");
        OffsetDateTime savedAt = jsonStorageService.saveJsonSetting(SETTINGS_KEY, normalized);
        return ResponseEntity.ok(new StoredSettingResponse(true, SETTINGS_KEY, savedAt));
    }

    @PostMapping("/service-enabled")
    public ResponseEntity<StoredSettingResponse> serviceEnabled(@RequestBody Map<String, Object> payload) {
        boolean enabled = booleanValue(payload.get("enabled"), true);
        Map<String, Object> settings = new LinkedHashMap<>(jsonStorageService.getJsonSetting(SETTINGS_KEY));
        settings.put("serviceEnabled", enabled);
        log.info("LeadProsvet service enabled changed: enabled={}", enabled);
        OffsetDateTime savedAt = jsonStorageService.saveJsonSetting(SETTINGS_KEY, settings);
        return ResponseEntity.ok(new StoredSettingResponse(true, SETTINGS_KEY, savedAt));
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

    private Map<String, String> field(String id, String label, String group) {
        return Map.of("id", id, "label", label, "group", group);
    }
}
