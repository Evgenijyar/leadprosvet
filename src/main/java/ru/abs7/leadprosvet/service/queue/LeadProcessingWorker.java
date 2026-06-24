package ru.abs7.leadprosvet.service.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LeadProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(LeadProcessingWorker.class);
    private static final String SETTINGS_KEY = "leadprosvet.settings";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([A-Z0-9_]+)}}");

    private final LeadProcessingJobRepository jobRepository;
    private final BitrixPortalService bitrixPortalService;
    private final BitrixRestClient bitrixRestClient;
    private final JsonStorageService jsonStorageService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private boolean disabledWorkerLogPrinted;

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
    }

    @Scheduled(fixedDelayString = "${app.queue.worker-delay-ms:1500}")
    public synchronized void processNextJob() {
        if (!isServiceEnabled()) {
            if (!disabledWorkerLogPrinted) {
                log.info("Lead processing worker paused: LeadProsvet service is disabled");
                disabledWorkerLogPrinted = true;
            }
            return;
        }
        disabledWorkerLogPrinted = false;
        jobRepository.firstPending().ifPresent(this::processJobSafely);
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

    private void processJobSafely(LeadProcessingJob job) {
        OffsetDateTime now = OffsetDateTime.now();
        job.setStatus("PROCESSING");
        job.setAttempt(job.getAttempt() + 1);
        job.setStartedAt(now);
        job.setUpdatedAt(now);
        job.setLastError(null);
        jobRepository.save(job);

        log.info("==================== LEAD JOB START ====================");
        log.info("Lead job started: jobId={}, eventLogId={}, event={}, leadId={}, attempt={}",
                job.getId(), job.getIncomingEventId(), job.getEventName(), job.getLeadId(), job.getAttempt());

        try {
            processJob(job);
            OffsetDateTime finishedAt = OffsetDateTime.now();
            job.setStatus("DONE");
            job.setFinishedAt(finishedAt);
            job.setUpdatedAt(finishedAt);
            jobRepository.save(job);
            log.info("Lead job DONE: jobId={}, leadId={}", job.getId(), job.getLeadId());
        } catch (Exception e) {
            OffsetDateTime failedAt = OffsetDateTime.now();
            captureLlmFailurePayload(job, e);

            boolean retryable = isRetryableFailure(e);
            job.setStatus(retryable && job.getAttempt() < 3 ? "PENDING" : "FAILED");
            job.setLastError(e.getMessage());
            job.setFinishedAt(failedAt);
            job.setUpdatedAt(failedAt);
            jobRepository.save(job);

            log.error("Lead job failed: jobId={}, leadId={}, retryable={}, nextStatus={}, error={}",
                    job.getId(), job.getLeadId(), retryable, job.getStatus(), e.getMessage(), e);
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

    private boolean isRetryableFailure(Exception e) {
        if (e instanceof LlmClient.LlmHttpException llmHttpException) {
            return llmHttpException.retryable();
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("llm api key is empty")
                || message.contains("api_key_invalid")
                || message.contains("api key not valid")
                || message.contains("invalid api key")) {
            return false;
        }
        return true;
    }

    private void processJob(LeadProcessingJob job) {
        BitrixPortal portal = bitrixPortalService.currentPortalOrThrow();
        Map<String, Object> settings = jsonStorageService.getJsonSetting(SETTINGS_KEY);

        Map<String, Object> leadPayload = bitrixRestClient.call(portal, "crm.lead.get", Map.of("id", job.getLeadId()));
        Object result = leadPayload.get("result");
        if (!(result instanceof Map<?, ?> leadRaw)) {
            throw new IllegalStateException("crm.lead.get returned empty lead for id=" + job.getLeadId());
        }

        Map<String, Object> lead = new LinkedHashMap<>();
        leadRaw.forEach((key, value) -> lead.put(String.valueOf(key), value));

        log.info("Bitrix lead loaded FULL: leadId={}, payload=\n{}", job.getLeadId(), toPrettyJson(leadPayload));

        String prompt = buildPrompt(settings, lead);
        job.setPromptText(prompt);
        jobRepository.save(job);
        log.info("Prompt for LLM FULL: jobId={}, leadId={}\n{}", job.getId(), job.getLeadId(), prompt);

        LlmClient.LlmResult llmResult = llmClient.generate(settings, prompt);
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
}
