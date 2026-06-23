package ru.abs7.leadprosvet.service.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs7.leadprosvet.config.AppProperties;
import ru.abs7.leadprosvet.domain.BitrixPortal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class BitrixSetupService {

    private static final Logger log = LoggerFactory.getLogger(BitrixSetupService.class);

    public static final String AI_FIELD_SHORT_NAME = "LP_AI_INFO";
    public static final String AI_FIELD_ID = "UF_CRM_" + AI_FIELD_SHORT_NAME;
    public static final String AI_FIELD_LABEL = "[ТЕХ.ПОЛЕ] ЛидПросвет";
    public static final String LEAD_ADD_EVENT = "ONCRMLEADADD";

    private final AppProperties appProperties;
    private final BitrixPortalService bitrixPortalService;
    private final BitrixRestClient bitrixRestClient;

    public BitrixSetupService(
            AppProperties appProperties,
            BitrixPortalService bitrixPortalService,
            BitrixRestClient bitrixRestClient
    ) {
        this.appProperties = appProperties;
        this.bitrixPortalService = bitrixPortalService;
        this.bitrixRestClient = bitrixRestClient;
    }

    public Map<String, Object> status() {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("portalDomain", nullToEmpty(portal.getDomain()));
        result.put("baseUrl", appProperties.baseUrl());
        result.put("leadAddEvent", LEAD_ADD_EVENT);
        result.put("leadAddHandler", leadAddHandlerUrl());
        result.put("aiFieldId", AI_FIELD_ID);
        result.put("aiFieldLabel", AI_FIELD_LABEL);
        result.put("aiFieldExists", aiFieldExists(portal));
        result.put("appInfo", safeCall(portal, "app.info", Map.of()));
        return result;
    }

    public Map<String, Object> runInitialSetup() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("createAiContactField", createAiContactField());
        result.put("bindLeadAddEvent", bindLeadAddEvent());
        result.put("status", status());
        return result;
    }

    public Map<String, Object> createAiContactField() {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fieldId", AI_FIELD_ID);
        result.put("fieldLabel", AI_FIELD_LABEL);

        if (aiFieldExists(portal)) {
            result.put("ok", true);
            result.put("alreadyExists", true);
            result.put("message", "Поле уже существует");
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
            Map<String, Object> payload = bitrixRestClient.call(portal, "crm.contact.userfield.add", Map.of("fields", fields));
            result.put("ok", true);
            result.put("alreadyExists", false);
            result.put("restResult", payload.get("result"));
            log.info("Bitrix contact user field {} created for portal {}", AI_FIELD_ID, portal.getDomain());
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> bindLeadAddEvent() {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        String handler = leadAddHandlerUrl();
        result.put("event", LEAD_ADD_EVENT);
        result.put("handler", handler);

        try {
            Map<String, Object> payload = bitrixRestClient.call(portal, "event.bind", Map.of(
                    "event", LEAD_ADD_EVENT,
                    "handler", handler
            ));
            result.put("ok", true);
            result.put("restResult", payload.get("result"));
            log.info("Bitrix event {} bound to {} for portal {}", LEAD_ADD_EVENT, handler, portal.getDomain());
        } catch (RuntimeException e) {
            String message = Objects.toString(e.getMessage(), "");
            result.put("ok", false);
            result.put("error", message);
            if (message.toLowerCase().contains("already") || message.toLowerCase().contains("exists")) {
                result.put("probablyAlreadyBound", true);
            }
        }
        return result;
    }

    private boolean aiFieldExists(BitrixPortal portal) {
        try {
            Map<String, Object> payload = bitrixRestClient.call(portal, "crm.contact.fields");
            Object rawResult = payload.get("result");
            if (rawResult instanceof Map<?, ?> fields) {
                return fields.containsKey(AI_FIELD_ID);
            }
        } catch (RuntimeException e) {
            log.warn("Cannot check Bitrix AI field existence: {}", e.getMessage());
        }
        return false;
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

    private String leadAddHandlerUrl() {
        return trimTrailingSlash(appProperties.baseUrl()) + "/api/bitrix/events/lead-add";
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
}
