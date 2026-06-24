package ru.abs7.leadprosvet.service.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.leadprosvet.domain.IncomingBitrixEvent;
import ru.abs7.leadprosvet.domain.LeadProcessingJob;
import ru.abs7.leadprosvet.repository.LeadProcessingJobRepository;
import ru.abs7.leadprosvet.service.JsonStorageService;
import ru.abs7.leadprosvet.service.bitrix.LeadTriggerModeService;
import ru.abs7.leadprosvet.service.llm.LlmClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LeadProcessingQueueService {

    private static final Logger log = LoggerFactory.getLogger(LeadProcessingQueueService.class);
    private static final String SETTINGS_KEY = "leadprosvet.settings";
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "PROCESSING");

    private final LeadProcessingJobRepository jobRepository;
    private final LeadTriggerModeService triggerModeService;
    private final JsonStorageService jsonStorageService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LeadProcessingQueueService(
            LeadProcessingJobRepository jobRepository,
            LeadTriggerModeService triggerModeService,
            JsonStorageService jsonStorageService,
            LlmClient llmClient,
            ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.triggerModeService = triggerModeService;
        this.jsonStorageService = jsonStorageService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> enqueueFromEvent(IncomingBitrixEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventLogId", event.getId());
        result.put("eventName", event.getEventName());
        result.put("leadId", event.getEntityId());

        if (!isServiceEnabled()) {
            result.put("queued", false);
            result.put("reason", "service_disabled");
            log.info("Bitrix event {} ignored because LeadProsvet service is disabled, eventLogId={}, leadId={}",
                    event.getEventName(), event.getId(), event.getEntityId());
            return result;
        }

        if (!triggerModeService.isSupported(event.getEventName())) {
            result.put("queued", false);
            result.put("reason", "unsupported_event");
            log.info("Bitrix event {} ignored: unsupported event, eventLogId={}", event.getEventName(), event.getId());
            return result;
        }

        if (!triggerModeService.isCurrent(event.getEventName())) {
            result.put("queued", false);
            result.put("reason", "event_not_selected_in_settings");
            result.put("selectedEvent", triggerModeService.currentEvent());
            log.info("Bitrix event {} ignored: selected trigger is {}, eventLogId={}", event.getEventName(), triggerModeService.currentEvent(), event.getId());
            return result;
        }

        if (event.getEntityId() == null || event.getEntityId().isBlank()) {
            result.put("queued", false);
            result.put("reason", "lead_id_not_found");
            log.warn("Bitrix event {} has no lead id, eventLogId={}", event.getEventName(), event.getId());
            return result;
        }

        String leadId = event.getEntityId().trim();
        if (jobRepository.existsByLeadIdAndStatusIn(leadId, ACTIVE_STATUSES)) {
            result.put("queued", false);
            result.put("reason", "lead_already_in_queue");
            log.info("Lead {} is already queued/processing, eventLogId={} ignored", leadId, event.getId());
            return result;
        }

        // Защита от бесконечной петли в режиме ONCRMLEADUPDATE: наш же crm.lead.update
        // тоже вызывает изменение лида. Сразу после успешной обработки повторный webhook по этому же лиду подавляем.
        if (LeadTriggerModeService.LEAD_UPDATE_EVENT.equals(event.getEventName())
                && jobRepository.existsRecentDoneByLeadId(leadId, OffsetDateTime.now().minusMinutes(2))) {
            result.put("queued", false);
            result.put("reason", "recently_processed_update_suppressed");
            log.info("Lead {} update webhook suppressed because it was processed less than 2 minutes ago, eventLogId={}", leadId, event.getId());
            return result;
        }

        LeadProcessingJob job = new LeadProcessingJob(
                event.getId(),
                event.getEventName(),
                event.getPortalDomain(),
                leadId,
                OffsetDateTime.now()
        );
        jobRepository.save(job);
        result.put("queued", true);
        result.put("jobId", job.getId());
        result.put("status", job.getStatus());
        log.info("Lead processing job queued: jobId={}, eventLogId={}, event={}, leadId={}", job.getId(), event.getId(), event.getEventName(), leadId);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> settings = jsonStorageService.getJsonSetting(SETTINGS_KEY);
        List<LlmClient.ApiKeySlot> slots = llmClient.apiKeySlots(settings);

        result.put("ok", true);
        result.put("pending", jobRepository.countByStatus("PENDING"));
        result.put("processing", jobRepository.countByStatus("PROCESSING"));
        result.put("done", jobRepository.countByStatus("DONE"));
        result.put("failed", jobRepository.countByStatus("FAILED"));
        result.put("serviceEnabled", isServiceEnabled());
        result.put("provider", llmClient.currentProvider(settings));
        result.put("apiKeyCount", slots.size());
        result.put("selectedEvent", triggerModeService.currentEvent());
        result.put("selectedEventLabel", triggerModeService.label(triggerModeService.currentEvent()));
        result.put("latest", jobRepository.findAllByOrderByIdDesc(Pageable.ofSize(20)).stream().map(this::view).toList());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> realtime(String firstFieldId, String secondFieldId) {
        Map<String, Object> settings = jsonStorageService.getJsonSetting(SETTINGS_KEY);
        List<LlmClient.ApiKeySlot> slots = llmClient.apiKeySlots(settings);
        List<LeadProcessingJob> jobs = jobRepository.findAllByStatusInOrderByIdAsc(List.of("PROCESSING", "PENDING"), Pageable.ofSize(500));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("serviceEnabled", isServiceEnabled());
        result.put("provider", llmClient.currentProvider(settings));
        result.put("apiKeyCount", slots.size());
        result.put("pending", jobRepository.countByStatus("PENDING"));
        result.put("processing", jobRepository.countByStatus("PROCESSING"));
        result.put("firstFieldId", value(firstFieldId));
        result.put("secondFieldId", value(secondFieldId));
        result.put("jobs", jobs.stream().map(job -> realtimeView(job, firstFieldId, secondFieldId)).toList());
        return result;
    }

    private boolean isServiceEnabled() {
        Map<String, Object> settings = jsonStorageService.getJsonSetting(SETTINGS_KEY);
        Object value = settings.get("serviceEnabled");
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return true;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
    }

    private Map<String, Object> realtimeView(LeadProcessingJob job, String firstFieldId, String secondFieldId) {
        Map<String, Object> lead = parseLeadSnapshot(job.getLeadSnapshotJson());
        Map<String, Object> map = view(job);
        map.put("firstValue", leadValue(job, lead, firstFieldId));
        map.put("secondValue", leadValue(job, lead, secondFieldId));
        return map;
    }

    private String leadValue(LeadProcessingJob job, Map<String, Object> lead, String fieldId) {
        if (fieldId == null || fieldId.isBlank()) {
            return "";
        }
        String key = fieldId.trim();
        if ("ID".equalsIgnoreCase(key) || "LEAD_ID".equalsIgnoreCase(key)) {
            return value(job.getLeadId());
        }
        Object direct = lead.get(key);
        if (direct == null) {
            return "";
        }
        if (direct instanceof String text) {
            return text;
        }
        if (direct instanceof Number || direct instanceof Boolean) {
            return String.valueOf(direct);
        }
        if (direct instanceof List<?> list) {
            return list.stream().map(this::formatValue).filter(item -> !item.isBlank()).reduce((a, b) -> a + "; " + b).orElse("");
        }
        if (direct instanceof Map<?, ?> map) {
            Object value = firstExisting(map, "VALUE", "value", "VALUE_FORMATTED", "valueFormatted");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(direct);
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            Object direct = firstExisting(map, "VALUE", "value", "VALUE_FORMATTED", "valueFormatted");
            if (direct != null) {
                return String.valueOf(direct);
            }
        }
        return String.valueOf(value);
    }

    private Object firstExisting(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLeadSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (JacksonException e) {
            log.warn("Cannot parse lead snapshot JSON for realtime queue: {}", e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> view(LeadProcessingJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.getId());
        map.put("eventLogId", job.getIncomingEventId());
        map.put("eventName", job.getEventName());
        map.put("portalDomain", job.getPortalDomain());
        map.put("leadId", job.getLeadId());
        map.put("status", job.getStatus());
        map.put("attempt", job.getAttempt());
        map.put("createdAt", value(job.getCreatedAt()));
        map.put("startedAt", value(job.getStartedAt()));
        map.put("finishedAt", value(job.getFinishedAt()));
        map.put("updatedAt", value(job.getUpdatedAt()));
        map.put("lastError", value(job.getLastError()));
        return map;
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
