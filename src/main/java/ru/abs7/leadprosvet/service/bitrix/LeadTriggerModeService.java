package ru.abs7.leadprosvet.service.bitrix;

import org.springframework.stereotype.Service;
import ru.abs7.leadprosvet.service.JsonStorageService;

import java.util.Map;
import java.util.Objects;

@Service
public class LeadTriggerModeService {

    public static final String SETTINGS_KEY = "leadprosvet.settings";
    public static final String LEAD_ADD_EVENT = "ONCRMLEADADD";
    public static final String LEAD_UPDATE_EVENT = "ONCRMLEADUPDATE";

    private final JsonStorageService jsonStorageService;

    public LeadTriggerModeService(JsonStorageService jsonStorageService) {
        this.jsonStorageService = jsonStorageService;
    }

    public String currentEvent() {
        Map<String, Object> settings = jsonStorageService.getJsonSetting(SETTINGS_KEY);
        String value = Objects.toString(settings.get("leadTriggerEvent"), "").trim();
        if (LEAD_UPDATE_EVENT.equals(value)) {
            return LEAD_UPDATE_EVENT;
        }
        return LEAD_ADD_EVENT;
    }

    public boolean isSupported(String eventName) {
        return LEAD_ADD_EVENT.equals(eventName) || LEAD_UPDATE_EVENT.equals(eventName);
    }

    public boolean isCurrent(String eventName) {
        return currentEvent().equals(eventName);
    }

    public String label(String eventName) {
        if (LEAD_UPDATE_EVENT.equals(eventName)) {
            return "Изменение лида";
        }
        return "Новый лид";
    }

    public String otherEvent(String eventName) {
        return LEAD_UPDATE_EVENT.equals(eventName) ? LEAD_ADD_EVENT : LEAD_UPDATE_EVENT;
    }
}
