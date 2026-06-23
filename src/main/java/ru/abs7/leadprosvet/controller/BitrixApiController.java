package ru.abs7.leadprosvet.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.leadprosvet.domain.BitrixPortal;
import ru.abs7.leadprosvet.service.bitrix.BitrixPortalService;
import ru.abs7.leadprosvet.service.bitrix.BitrixRestClient;
import ru.abs7.leadprosvet.service.bitrix.BitrixRestException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bitrix")
public class BitrixApiController {

    private static final Logger log = LoggerFactory.getLogger(BitrixApiController.class);

    private final BitrixPortalService bitrixPortalService;
    private final BitrixRestClient bitrixRestClient;

    public BitrixApiController(BitrixPortalService bitrixPortalService, BitrixRestClient bitrixRestClient) {
        this.bitrixPortalService = bitrixPortalService;
        this.bitrixRestClient = bitrixRestClient;
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics() {
        Map<String, Object> response = new LinkedHashMap<>();
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        response.put("ok", true);
        response.put("portal", portalView(portal));
        response.put("appInfo", safeRestCall(portal, "app.info"));
        response.put("userAdmin", safeRestCall(portal, "user.admin"));
        response.put("userCurrent", Map.of(
                "ok", false,
                "skipped", true,
                "reason", "user.current не вызывается: у локального приложения может не быть user-scope, для нас достаточно user.admin"
        ));
        response.put("contactFields", contactFieldsSummary(portal));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/contact-fields")
    public ResponseEntity<List<Map<String, String>>> contactFields() {
        try {
            BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
            List<Map<String, String>> fields = loadContactFields(portal);
            if (!fields.isEmpty()) {
                return ResponseEntity.ok(fields);
            }
        } catch (RuntimeException e) {
            log.warn("Cannot load real Bitrix contact fields, using fallback: {}", e.getMessage());
        }
        return ResponseEntity.ok(fallbackContactFields());
    }

    private Map<String, Object> contactFieldsSummary(BitrixPortal portal) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, String>> fields = loadContactFields(portal);
            result.put("ok", true);
            result.put("count", fields.size());
            result.put("sample", fields.stream().limit(12).toList());
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> loadContactFields(BitrixPortal portal) {
        Map<String, Object> payload = bitrixRestClient.call(portal, "crm.contact.fields");
        Object result = payload.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return List.of();
        }

        List<Map<String, String>> fields = new ArrayList<>();
        resultMap.forEach((idObject, fieldObject) -> {
            String id = String.valueOf(idObject);
            if (!(fieldObject instanceof Map<?, ?> fieldMap)) {
                fields.add(field(id, id, "Контакт"));
                return;
            }
            String label = firstNonBlank(
                    stringValue(fieldMap.get("formLabel")),
                    stringValue(fieldMap.get("listLabel")),
                    stringValue(fieldMap.get("filterLabel")),
                    stringValue(fieldMap.get("title")),
                    id
            );
            String type = firstNonBlank(stringValue(fieldMap.get("type")), stringValue(fieldMap.get("userTypeId")), "field");
            String group = id.startsWith("UF_") ? "Пользовательское поле" : "Контакт · " + type;
            fields.add(field(id, label, group));
        });

        fields.sort(Comparator
                .comparing((Map<String, String> item) -> item.getOrDefault("group", ""))
                .thenComparing(item -> item.getOrDefault("label", ""))
                .thenComparing(item -> item.getOrDefault("id", "")));
        return fields;
    }

    private Map<String, Object> safeRestCall(BitrixPortal portal, String method) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> payload = bitrixRestClient.call(portal, method);
            result.put("ok", true);
            result.put("result", payload.get("result"));
            result.put("time", payload.get("time"));
        } catch (BitrixRestException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> portalView(BitrixPortal portal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", portal.getId());
        map.put("domain", value(portal.getDomain()));
        map.put("memberId", value(portal.getMemberId()));
        map.put("clientEndpoint", value(portal.getClientEndpoint()));
        map.put("serverEndpoint", value(portal.getServerEndpoint()));
        map.put("installed", portal.isInstalled());
        map.put("hasAccessToken", hasText(portal.getAccessToken()));
        map.put("hasRefreshToken", hasText(portal.getRefreshToken()));
        map.put("hasApplicationToken", hasText(portal.getApplicationToken()));
        map.put("installedAt", value(portal.getInstalledAt()));
        map.put("updatedAt", value(portal.getUpdatedAt()));
        return map;
    }

    private List<Map<String, String>> fallbackContactFields() {
        return List.of(
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
        );
    }

    private Map<String, String> field(String id, String label, String group) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        map.put("group", group);
        return map;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Object value(Object value) {
        return value == null ? "" : value;
    }
}
