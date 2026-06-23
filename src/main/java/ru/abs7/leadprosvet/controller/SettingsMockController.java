package ru.abs7.leadprosvet.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsMockController {

    private static final Logger log = LoggerFactory.getLogger(SettingsMockController.class);

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

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        log.info("Mock settings saved: {}", payload);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "message", "Настройки приняты моковым endpoint. Сохранение в БД добавим следующим шагом.",
                "savedAt", OffsetDateTime.now().toString()
        ));
    }

    private Map<String, String> field(String id, String label, String group) {
        return Map.of("id", id, "label", label, "group", group);
    }
}
