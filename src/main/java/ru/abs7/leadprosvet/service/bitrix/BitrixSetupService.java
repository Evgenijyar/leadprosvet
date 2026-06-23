package ru.abs7.leadprosvet.service.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.leadprosvet.config.AppProperties;
import ru.abs7.leadprosvet.domain.BitrixPortal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class BitrixSetupService {

    private static final Logger log = LoggerFactory.getLogger(BitrixSetupService.class);

    public static final String AI_FIELD_SHORT_NAME = "LP_AI_INFO";
    public static final String AI_FIELD_ID = "UF_CRM_" + AI_FIELD_SHORT_NAME;
    public static final String AI_FIELD_LABEL = "[ТЕХ.ПОЛЕ] ЛидПросвет";
    
    private final AppProperties appProperties;
    private final BitrixPortalService bitrixPortalService;
    private final BitrixRestClient bitrixRestClient;
    private final LeadTriggerModeService triggerModeService;

    public BitrixSetupService(
            AppProperties appProperties,
            BitrixPortalService bitrixPortalService,
            BitrixRestClient bitrixRestClient,
            LeadTriggerModeService triggerModeService
    ) {
        this.appProperties = appProperties;
        this.bitrixPortalService = bitrixPortalService;
        this.bitrixRestClient = bitrixRestClient;
        this.triggerModeService = triggerModeService;
    }

    public Map<String, Object> status() {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        FieldCheck fieldCheck = checkAiLeadField(portal);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("portalDomain", nullToEmpty(portal.getDomain()));
        result.put("baseUrl", appProperties.baseUrl());
        result.put("crmEntity", "lead");
        result.put("fieldsRestMethod", "crm.lead.fields");
        result.put("userFieldCreateRestMethod", "crm.lead.userfield.add");
        result.put("leadFieldsEndpoint", "/api/bitrix/lead-fields");
        String currentEvent = triggerModeService.currentEvent();
        result.put("selectedLeadEvent", currentEvent);
        result.put("selectedLeadEventLabel", triggerModeService.label(currentEvent));
        result.put("leadAddEvent", LeadTriggerModeService.LEAD_ADD_EVENT);
        result.put("leadUpdateEvent", LeadTriggerModeService.LEAD_UPDATE_EVENT);
        result.put("leadEventHandler", leadEventHandlerUrl());
        result.put("aiFieldId", AI_FIELD_ID);
        result.put("aiFieldLabel", AI_FIELD_LABEL);
        result.put("aiFieldTargetEntity", "lead");
        result.put("aiFieldExists", fieldCheck.exists());
        result.put("aiFieldExact", fieldCheck.exact());
        result.put("aiFieldMatchesByLabel", fieldCheck.matchesByLabel());
        result.put("aiFieldCheckError", fieldCheck.error());
        result.put("appInfo", safeCall(portal, "app.info", Map.of()));
        return result;
    }

    public Map<String, Object> runInitialSetup() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("createAiLeadField", createAiLeadField());
        result.put("bindSelectedLeadEvent", bindLeadAddEvent());
        result.put("status", status());
        return result;
    }

    public Map<String, Object> createAiLeadField() {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", "lead");
        result.put("fieldId", AI_FIELD_ID);
        result.put("fieldShortName", AI_FIELD_SHORT_NAME);
        result.put("fieldLabel", AI_FIELD_LABEL);

        FieldCheck before = checkAiLeadField(portal);
        result.put("before", before.toMap());
        if (before.exists()) {
            result.put("ok", true);
            result.put("alreadyExists", true);
            result.put("message", "Поле лида уже существует");
            return result;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("FIELD_NAME", AI_FIELD_SHORT_NAME);
        fields.put("USER_TYPE_ID", "string");
        fields.put("XML_ID", AI_FIELD_ID);
        fields.put("SORT", 500);
        fields.put("MULTIPLE", "N");
        fields.put("MANDATORY", "N");
        fields.put("SHOW_FILTER", "N");
        fields.put("SHOW_IN_LIST", "N");
        fields.put("EDIT_IN_LIST", "Y");
        fields.put("IS_SEARCHABLE", "N");
        fields.put("EDIT_FORM_LABEL", Map.of("ru", AI_FIELD_LABEL, "en", "LeadProsvet"));
        fields.put("LIST_COLUMN_LABEL", Map.of("ru", AI_FIELD_LABEL, "en", "LeadProsvet"));
        fields.put("LIST_FILTER_LABEL", Map.of("ru", AI_FIELD_LABEL, "en", "LeadProsvet"));
        fields.put("SETTINGS", Map.of("ROWS", 18));

        try {
            Map<String, Object> payload = bitrixRestClient.call(portal, "crm.lead.userfield.add", Map.of("fields", fields));
            FieldCheck after = checkAiLeadField(portal);
            result.put("ok", after.exists());
            result.put("alreadyExists", false);
            result.put("restMethod", "crm.lead.userfield.add");
            result.put("restResult", payload.get("result"));
            result.put("after", after.toMap());
            if (!after.exists()) {
                result.put("warning", "Bitrix вернул успешный ответ, но поле пока не найдено в crm.lead.fields. Проверь /api/bitrix/setup/status через 5-10 секунд.");
            }
            log.info("Bitrix lead user field {} create call completed for portal {}. Exists after call: {}", AI_FIELD_ID, portal.getDomain(), after.exists());
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            log.warn("Cannot create Bitrix lead user field {} for portal {}: {}", AI_FIELD_ID, portal.getDomain(), e.getMessage());
        }
        return result;
    }

    public Map<String, Object> bindLeadAddEvent() {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        String selectedEvent = triggerModeService.currentEvent();
        String handler = leadEventHandlerUrl();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selectedEvent", selectedEvent);
        result.put("selectedEventLabel", triggerModeService.label(selectedEvent));
        result.put("handler", handler);
        result.put("unbindOldHandlers", unbindStaleLeadHandlers(portal, selectedEvent, handler));

        try {
            Map<String, Object> payload = bitrixRestClient.call(portal, "event.bind", Map.of(
                    "event", selectedEvent,
                    "handler", handler
            ));
            result.put("ok", true);
            result.put("restResult", payload.get("result"));
            log.info("Bitrix selected event {} bound to {} for portal {}", selectedEvent, handler, portal.getDomain());
        } catch (RuntimeException e) {
            String message = Objects.toString(e.getMessage(), "");
            boolean alreadyBound = message.toLowerCase().contains("already") || message.toLowerCase().contains("exists");
            result.put("ok", alreadyBound);
            result.put("error", message);
            if (alreadyBound) {
                result.put("probablyAlreadyBound", true);
            }
            log.warn("Cannot bind Bitrix selected event {} to {} for portal {}: {}", selectedEvent, handler, portal.getDomain(), message);
        }
        return result;
    }

    private List<Map<String, Object>> unbindStaleLeadHandlers(BitrixPortal portal, String selectedEvent, String selectedHandler) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> events = List.of(LeadTriggerModeService.LEAD_ADD_EVENT, LeadTriggerModeService.LEAD_UPDATE_EVENT);
        List<String> handlers = List.of(
                leadEventHandlerUrl(),
                legacyLeadAddHandlerUrl(),
                legacyLeadUpdateHandlerUrl()
        );

        for (String event : events) {
            for (String handler : handlers) {
                if (event.equals(selectedEvent) && handler.equals(selectedHandler)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("event", event);
                item.put("handler", handler);
                try {
                    Map<String, Object> payload = bitrixRestClient.call(portal, "event.unbind", Map.of(
                            "event", event,
                            "handler", handler
                    ));
                    item.put("ok", true);
                    item.put("restResult", payload.get("result"));
                    log.info("Bitrix stale event handler unbound: event={}, handler={}, portal={}", event, handler, portal.getDomain());
                } catch (RuntimeException e) {
                    item.put("ok", false);
                    item.put("ignored", true);
                    item.put("error", e.getMessage());
                    log.info("Bitrix stale event handler unbind ignored: event={}, handler={}, portal={}, error={}", event, handler, portal.getDomain(), e.getMessage());
                }
                results.add(item);
            }
        }
        return results;
    }

    private FieldCheck checkAiLeadField(BitrixPortal portal) {
        Map<String, Object> exact = null;
        List<Map<String, Object>> matchesByLabel = new ArrayList<>();
        String error = "";

        try {
            Map<String, Object> payload = bitrixRestClient.call(portal, "crm.lead.fields");
            Object rawResult = payload.get("result");
            if (rawResult instanceof Map<?, ?> fields) {
                for (Map.Entry<?, ?> entry : fields.entrySet()) {
                    String id = String.valueOf(entry.getKey());
                    if (!(entry.getValue() instanceof Map<?, ?> fieldMap)) {
                        continue;
                    }
                    Map<String, Object> view = fieldView(id, fieldMap);
                    if (AI_FIELD_ID.equals(id)) {
                        exact = view;
                    }
                    String label = Objects.toString(view.get("label"), "").trim();
                    if (AI_FIELD_LABEL.equals(label)) {
                        matchesByLabel.add(view);
                    }
                }
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
            log.warn("Cannot check Bitrix AI lead field existence through crm.lead.fields: {}", error);
        }

        if (exact == null) {
            try {
                Map<String, Object> payload = bitrixRestClient.call(portal, "crm.lead.userfield.list");
                Object rawResult = payload.get("result");
                if (rawResult instanceof Iterable<?> items) {
                    for (Object item : items) {
                        if (!(item instanceof Map<?, ?> fieldMap)) {
                            continue;
                        }
                        String id = firstNonBlank(
                                stringValue(fieldMap.get("FIELD_NAME")),
                                stringValue(fieldMap.get("fieldName")),
                                stringValue(fieldMap.get("ID"))
                        );
                        Map<String, Object> view = fieldView(id, fieldMap);
                        if (AI_FIELD_ID.equals(id)) {
                            exact = view;
                        }
                        String label = Objects.toString(view.get("label"), "").trim();
                        if (AI_FIELD_LABEL.equals(label) && matchesByLabel.stream().noneMatch(existing -> Objects.equals(existing.get("id"), view.get("id")))) {
                            matchesByLabel.add(view);
                        }
                    }
                }
            } catch (RuntimeException e) {
                if (error.isBlank()) {
                    error = e.getMessage();
                } else {
                    error = error + " | crm.lead.userfield.list: " + e.getMessage();
                }
                log.warn("Cannot check Bitrix AI lead field existence through crm.lead.userfield.list: {}", e.getMessage());
            }
        }

        return new FieldCheck(exact != null || !matchesByLabel.isEmpty(), exact, matchesByLabel, error);
    }

    private Map<String, Object> fieldView(String id, Map<?, ?> fieldMap) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("label", firstNonBlank(
                stringValue(fieldMap.get("formLabel")),
                stringValue(fieldMap.get("EDIT_FORM_LABEL")),
                stringValue(fieldMap.get("listLabel")),
                stringValue(fieldMap.get("LIST_COLUMN_LABEL")),
                stringValue(fieldMap.get("filterLabel")),
                stringValue(fieldMap.get("LIST_FILTER_LABEL")),
                stringValue(fieldMap.get("title")),
                id
        ));
        view.put("type", firstNonBlank(
                stringValue(fieldMap.get("type")),
                stringValue(fieldMap.get("USER_TYPE_ID")),
                stringValue(fieldMap.get("userTypeId")),
                ""
        ));
        return view;
    }

    private Map<String, Object> safeCall(BitrixPortal portal, String method, Map<String, ?> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> payload = bitrixRestClient.call(portal, method, params);
            result.put("ok", true);
            result.put("result", payload.get("result"));
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private String leadEventHandlerUrl() {
        return trimTrailingSlash(appProperties.baseUrl()) + "/api/bitrix/events/lead";
    }

    private String legacyLeadAddHandlerUrl() {
        return trimTrailingSlash(appProperties.baseUrl()) + "/api/bitrix/events/lead-add";
    }

    private String legacyLeadUpdateHandlerUrl() {
        return trimTrailingSlash(appProperties.baseUrl()) + "/api/bitrix/events/lead-update";
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
        if (value instanceof Map<?, ?> map) {
            Object ru = map.get("ru");
            if (ru != null && !String.valueOf(ru).isBlank()) {
                return String.valueOf(ru);
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    private record FieldCheck(boolean exists, Map<String, Object> exact, List<Map<String, Object>> matchesByLabel, String error) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", exists);
            result.put("exact", exact);
            result.put("matchesByLabel", matchesByLabel);
            result.put("error", error);
            return result;
        }
    }
}
