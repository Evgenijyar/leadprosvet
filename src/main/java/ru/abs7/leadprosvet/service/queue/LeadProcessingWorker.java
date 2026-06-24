package ru.abs7.leadprosvet.service.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.abs7.leadprosvet.domain.BitrixPortal;
import ru.abs7.leadprosvet.domain.LeadProcessingJob;
import ru.abs7.leadprosvet.repository.LeadProcessingJobRepository;
import ru.abs7.leadprosvet.service.JsonStorageService;
import ru.abs7.leadprosvet.service.bitrix.BitrixPortalService;
import ru.abs7.leadprosvet.service.bitrix.BitrixRestClient;
import ru.abs7.leadprosvet.service.bitrix.BitrixSetupService;
import ru.abs7.leadprosvet.service.llm.LlmClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LeadProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(LeadProcessingWorker.class);
    private static final String SETTINGS_KEY = "leadprosvet.settings";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([A-Z0-9_]+)}}");
    private static final int MAX_LLM_ATTEMPTS_PER_JOB_CLAIM = 5;
    private static final long LLM_RETRY_SLEEP_MS = 10_000L;

    private final LeadProcessingJobRepository jobRepository;
    private final BitrixPortalService bitrixPortalService;
    private final BitrixRestClient bitrixRestClient;
    private final JsonStorageService jsonStorageService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Set<String> activeSlots = ConcurrentHashMap.newKeySet();
    private boolean disabledWorkerLogPrinted;
    private boolean noKeysLogPrinted;

    public LeadProcessingWorker(
            LeadProcessingJobRepository jobRepository,
            BitrixPortalService bitrixPortalService,
            BitrixRestClient bitrixRestClient,
            JsonStorageService jsonStorageService,
            LlmClient llmClient,
            ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.bitrixPortalService = bitrixPortalService;
        this.bitrixRestClient = bitrixRestClient;
        this.jsonStorageService = jsonStorageService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.executor = Executors.newCachedThreadPool(new NamedWorkerThreadFactory());
    }

    @Scheduled(fixedDelayString = "${app.queue.worker-delay-ms:1500}")
    public void processQueueTick() {
        if (!isServiceEnabled()) {
            if (!disabledWorkerLogPrinted) {
                log.info("Lead processing workers paused: LeadProsvet service is disabled");
                disabledWorkerLogPrinted = true;
            }
            return;
        }
        disabledWorkerLogPrinted = false;

        Map<String, Object> settings = jsonStorageService.getJsonSetting(SETTINGS_KEY);
        List<LlmClient.ApiKeySlot> slots = llmClient.apiKeySlots(settings);
        if (slots.isEmpty()) {
            if (!noKeysLogPrinted) {
                log.info("Lead processing workers paused: no active LLM API keys configured for provider={}", llmClient.currentProvider(settings));
                noKeysLogPrinted = true;
            }
            return;
        }
        noKeysLogPrinted = false;

        for (LlmClient.ApiKeySlot slot : slots) {
            if (!activeSlots.add(slot.slotId())) {
                continue;
            }
            executor.submit(() -> processOneJobForSlot(slot));
        }
    }

    private void processOneJobForSlot(LlmClient.ApiKeySlot slot) {
        try {
            Optional<LeadProcessingJob> claimed = claimNextPendingJob();
            if (claimed.isEmpty()) {
                return;
            }
            processClaimedJobSafely(claimed.get(), slot);
        } finally {
            activeSlots.remove(slot.slotId());
        }
    }

    private Optional<LeadProcessingJob> claimNextPendingJob() {
        List<LeadProcessingJob> candidates = jobRepository.findAllByStatusOrderByIdAsc("PENDING", Pageable.ofSize(20));
        for (LeadProcessingJob candidate : candidates) {
            int claimed = jobRepository.claimPendingJob(candidate.getId(), OffsetDateTime.now());
            if (claimed == 1) {
                return jobRepository.findById(candidate.getId());
            }
        }
        return Optional.empty();
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

    private void processClaimedJobSafely(LeadProcessingJob job, LlmClient.ApiKeySlot slot) {
        log.info("==================== LEAD JOB START ====================");
        log.info("Lead job started: jobId={}, eventLogId={}, event={}, leadId={}, workerKey={}",
                job.getId(), job.getIncomingEventId(), job.getEventName(), job.getLeadId(), slot.label());

        try {
            boolean done = processJob(job, slot);
            if (!done) {
                log.info("Lead job left in queue: jobId={}, leadId={}, workerKey={}, status={}",
                        job.getId(), job.getLeadId(), slot.label(), job.getStatus());
                return;
            }
            OffsetDateTime finishedAt = OffsetDateTime.now();
            job.setStatus("DONE");
            job.setAttempt(0);
            job.setFinishedAt(finishedAt);
            job.setUpdatedAt(finishedAt);
            jobRepository.save(job);
            log.info("Lead job DONE: jobId={}, leadId={}, workerKey={}", job.getId(), job.getLeadId(), slot.label());
        } catch (Exception e) {
            OffsetDateTime failedAt = OffsetDateTime.now();
            captureLlmFailurePayload(job, e);

            // If a non-LLM error happened outside the five internal LLM attempts, return the lead
            // to the queue so another worker/key can pick it later. This keeps morning batches moving.
            job.setStatus("PENDING");
            job.setAttempt(0);
            job.setLastError(e.getMessage());
            job.setFinishedAt(failedAt);
            job.setUpdatedAt(failedAt);
            jobRepository.save(job);

            log.error("Lead job returned to PENDING after infrastructure error: jobId={}, leadId={}, workerKey={}, error={}",
                    job.getId(), job.getLeadId(), slot.label(), e.getMessage(), e);
        } finally {
            log.info("==================== LEAD JOB END ====================");
        }
    }

    private void captureLlmFailurePayload(LeadProcessingJob job, Exception e) {
        if (e instanceof LlmClient.LlmHttpException llmHttpException) {
            job.setLlmRequest(llmHttpException.requestJson());
            job.setLlmResponse(llmHttpException.responseBody());
        }
    }

    private boolean processJob(LeadProcessingJob job, LlmClient.ApiKeySlot slot) {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        Map<String, Object> settings = jsonStorageService.getJsonSetting(SETTINGS_KEY);

        Map<String, Object> leadPayload = bitrixRestClient.call(portal, "crm.lead.get", Map.of("id", job.getLeadId()));
        Object result = leadPayload.get("result");
        if (!(result instanceof Map<?, ?> leadRaw)) {
            throw new IllegalStateException("crm.lead.get returned empty lead for id=" + job.getLeadId());
        }

        Map<String, Object> lead = new LinkedHashMap<>();
        leadRaw.forEach((key, value) -> lead.put(String.valueOf(key), value));
        job.setLeadSnapshotJson(toPrettyJson(lead));
        jobRepository.save(job);

        log.info("Bitrix lead loaded FULL: leadId={}, payload=\n{}", job.getLeadId(), toPrettyJson(leadPayload));

        String prompt = buildPrompt(settings, lead);
        job.setPromptText(prompt);
        jobRepository.save(job);
        log.info("Prompt for LLM FULL: jobId={}, leadId={}\n{}", job.getId(), job.getLeadId(), prompt);

        LlmClient.LlmResult llmResult = null;
        RuntimeException lastLlmError = null;
        for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS_PER_JOB_CLAIM; attempt++) {
            markLlmAttempt(job, attempt);
            try {
                log.info("LLM attempt started: jobId={}, leadId={}, attempt={}/{}, workerKey={}",
                        job.getId(), job.getLeadId(), attempt, MAX_LLM_ATTEMPTS_PER_JOB_CLAIM, slot.label());
                llmResult = llmClient.generate(settings, prompt, slot);
                break;
            } catch (RuntimeException e) {
                lastLlmError = e;
                captureLlmFailurePayload(job, e);
                job.setLastError(e.getMessage());
                job.setUpdatedAt(OffsetDateTime.now());
                jobRepository.save(job);
                log.warn("LLM attempt failed: jobId={}, leadId={}, attempt={}/{}, workerKey={}, error={}",
                        job.getId(), job.getLeadId(), attempt, MAX_LLM_ATTEMPTS_PER_JOB_CLAIM, slot.label(), e.getMessage());

                if (attempt < MAX_LLM_ATTEMPTS_PER_JOB_CLAIM) {
                    sleepBeforeNextLlmAttempt(job, slot, attempt);
                }
            }
        }

        if (llmResult == null) {
            OffsetDateTime now = OffsetDateTime.now();
            job.setStatus("PENDING");
            job.setAttempt(0);
            job.setFinishedAt(now);
            job.setUpdatedAt(now);
            job.setLastError(lastLlmError == null ? "LLM failed after 5 attempts" : lastLlmError.getMessage());
            jobRepository.save(job);
            log.error("Lead job reset to PENDING after {} failed LLM attempts: jobId={}, leadId={}, workerKey={}",
                    MAX_LLM_ATTEMPTS_PER_JOB_CLAIM, job.getId(), job.getLeadId(), slot.label());
            return false;
        }

        job.setLlmRequest(llmResult.requestJson());
        job.setLlmResponse(llmResult.responseJson());
        jobRepository.save(job);

        Map<String, Object> updateParams = Map.of(
                "id", job.getLeadId(),
                "fields", Map.of(BitrixSetupService.AI_FIELD_ID, llmResult.text())
        );
        log.info("Bitrix lead update request FULL: method=crm.lead.update, params=\n{}", toPrettyJson(updateParams));
        Map<String, Object> updateResponse = bitrixRestClient.call(portal, "crm.lead.update", updateParams);
        String updateResponseJson = toPrettyJson(updateResponse);
        job.setBitrixUpdateResponse(updateResponseJson);
        jobRepository.save(job);
        log.info("Bitrix lead update response FULL: jobId={}, leadId={}\n{}", job.getId(), job.getLeadId(), updateResponseJson);
        return true;
    }

    private void markLlmAttempt(LeadProcessingJob job, int attempt) {
        job.setStatus("PROCESSING");
        job.setAttempt(attempt);
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.save(job);
    }

    private void sleepBeforeNextLlmAttempt(LeadProcessingJob job, LlmClient.ApiKeySlot slot, int completedAttempt) {
        try {
            log.info("LLM retry pause: jobId={}, leadId={}, workerKey={}, completedAttempt={}, sleepMs={}",
                    job.getId(), job.getLeadId(), slot.label(), completedAttempt, LLM_RETRY_SLEEP_MS);
            Thread.sleep(LLM_RETRY_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM retry sleep interrupted", e);
        }
    }

    private String buildPrompt(Map<String, Object> settings, Map<String, Object> lead) {
        String template = Objects.toString(settings.get("promptTemplate"), "").trim();
        if (template.isBlank()) {
            template = defaultPromptTemplate();
        }

        Matcher matcher = TOKEN_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String fieldId = matcher.group(1);
            String replacement = formatLeadValue(lead.get(fieldId));
            log.info("Prompt field injected: fieldId={}, valuePresent={}", fieldId, replacement != null && !replacement.isBlank());
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        // ВАЖНО: не добавляем сюда полный JSON лида и не добавляем никакие скрытые поля.
        // В LLM уходит только текст шаблона и только те Bitrix-поля, которые пользователь явно вставил в шаблон через {{FIELD_ID}}.
        return result.toString();
    }

    private String defaultPromptTemplate() {
        return """
                Собери краткую справку по лиду для менеджера.

                Вставь в этот шаблон только те поля лида, которые действительно нужно отправить в LLM.
                Приложение не добавляет полный JSON лида автоматически.
                """;
    }

    private String formatLeadValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::formatLeadValue).filter(item -> !item.isBlank()).reduce((a, b) -> a + "; " + b).orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            Object direct = firstExisting(map, "VALUE", "value", "VALUE_FORMATTED", "valueFormatted");
            if (direct != null) {
                return String.valueOf(direct);
            }
            return toPrettyJson(map);
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

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    private static final class NamedWorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lead-llm-worker-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
