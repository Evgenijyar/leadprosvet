package ru.abs7.leadprosvet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.leadprosvet.domain.AppSetting;
import ru.abs7.leadprosvet.repository.AppSettingRepository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JsonStorageService {

    private final AppSettingRepository appSettingRepository;
    private final ObjectMapper objectMapper;

    public JsonStorageService(AppSettingRepository appSettingRepository, ObjectMapper objectMapper) {
        this.appSettingRepository = appSettingRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OffsetDateTime saveJsonSetting(String key, Object payload) {
        OffsetDateTime now = OffsetDateTime.now();
        String json = toJson(payload);
        AppSetting setting = appSettingRepository.findById(key)
                .orElseGet(() -> new AppSetting(key, json, now));
        setting.setValue(json);
        setting.setUpdatedAt(now);
        appSettingRepository.save(setting);
        return now;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getJsonSetting(String key) {
        return appSettingRepository.findById(key)
                .map(AppSetting::getValue)
                .map(this::fromJsonObject)
                .orElseGet(LinkedHashMap::new);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize setting payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonObject(String json) {
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return new LinkedHashMap<>();
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }
}
