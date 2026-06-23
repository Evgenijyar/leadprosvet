package ru.abs7.leadprosvet.service.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.leadprosvet.domain.IncomingBitrixEvent;
import ru.abs7.leadprosvet.domain.LeadProcessingJob;
import ru.abs7.leadprosvet.repository.LeadProcessingJobRepository;
import ru.abs7.leadprosvet.service.bitrix.LeadTriggerModeService;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class LeadProcessingQueueService {

    private static final Logger log = LoggerFactory.getLogger(LeadProcessingQueueService.class);
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "PROCESSING");

    private final LeadProcessingJobRepository jobRepository;
    private final LeadTriggerModeService triggerModeService;

    public LeadProcessingQueueService(LeadProcessingJobRepository jobRepository, LeadTriggerModeService triggerModeService) {
        this.jobRepository = jobRepository;
        this.triggerModeService = triggerModeService;
    }

    @Transactional
    public Map<String, Object> enqueueFromEvent(IncomingBitrixEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventLogId", event.getId());
        result.put("eventName", event.getEventName());
        result.put("leadId", event.getEntityId());

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
        result.put("ok", true);
        result.put("pending", jobRepository.countByStatus("PENDING"));
        result.put("processing", jobRepository.countByStatus("PROCESSING"));
        result.put("done", jobRepository.countByStatus("DONE"));
        result.put("failed", jobRepository.countByStatus("FAILED"));
        result.put("selectedEvent", triggerModeService.currentEvent());
        result.put("selectedEventLabel", triggerModeService.label(triggerModeService.currentEvent()));
        result.put("latest", jobRepository.findAllByOrderByIdDesc(Pageable.ofSize(20)).stream().map(this::view).toList());
        return result;
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
        map.put("lastError", value(job.getLastError()));
        return map;
    }

    private Object value(Object value) {
        return value == null ? "" : value;
    }
}
