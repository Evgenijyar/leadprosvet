package ru.abs7.leadprosvet.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.leadprosvet.domain.IncomingBitrixEvent;
import ru.abs7.leadprosvet.service.BitrixEventLogService;
import ru.abs7.leadprosvet.service.queue.LeadProcessingQueueService;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BitrixEventController {

    private static final Logger log = LoggerFactory.getLogger(BitrixEventController.class);

    private final BitrixEventLogService bitrixEventLogService;
    private final LeadProcessingQueueService leadProcessingQueueService;

    public BitrixEventController(BitrixEventLogService bitrixEventLogService, LeadProcessingQueueService leadProcessingQueueService) {
        this.bitrixEventLogService = bitrixEventLogService;
        this.leadProcessingQueueService = leadProcessingQueueService;
    }

    @PostMapping(value = {
            "/api/bitrix/events",
            "/api/bitrix/events/lead",
            "/api/bitrix/events/lead-add",
            "/api/bitrix/events/lead-update"
    }, consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> receiveEvent(
            @RequestParam Map<String, String> params,
            @RequestBody(required = false) String body,
            HttpServletRequest request
    ) {
        log.info("==================== BITRIX WEBHOOK RECEIVED START ====================");
        log.info("Bitrix webhook remoteIp={}", request.getRemoteAddr());
        log.info("Bitrix webhook params SAFE={}", safeParams(params));
        log.info("Bitrix webhook raw body SAFE=\n{}", safeBody(body));
        log.info("==================== BITRIX WEBHOOK RECEIVED END ====================");

        IncomingBitrixEvent event = bitrixEventLogService.saveIncomingEvent(params, body, request.getRemoteAddr());
        Map<String, Object> queueResult = leadProcessingQueueService.enqueueFromEvent(event);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("status", "accepted");
        response.put("eventLogId", event.getId());
        response.put("queue", queueResult);
        response.put("message", "Bitrix event saved and queue decision completed by LeadProsvet");
        response.put("serverTime", OffsetDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    private Map<String, String> safeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        return params.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> maskIfSecret(entry.getKey(), entry.getValue())
        ));
    }

    private String safeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return body
                .replaceAll("(?i)(access_token=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(refresh_token=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(auth=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(application_token=)[^&\\s]+", "$1***");
    }

    private String maskIfSecret(String key, String value) {
        if (value == null) {
            return "";
        }
        String lowerKey = key == null ? "" : key.toLowerCase();
        if (lowerKey.contains("token") || lowerKey.contains("auth") || lowerKey.contains("key")) {
            return "***";
        }
        return value;
    }
}
