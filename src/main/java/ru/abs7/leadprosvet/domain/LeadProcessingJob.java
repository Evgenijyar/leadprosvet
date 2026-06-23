package ru.abs7.leadprosvet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "lead_processing_jobs")
public class LeadProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incoming_event_id")
    private Long incomingEventId;

    @Column(name = "event_name", length = 120)
    private String eventName;

    @Column(name = "portal_domain", length = 255)
    private String portalDomain;

    @Column(name = "lead_id", nullable = false, length = 80)
    private String leadId;

    @Column(name = "status", nullable = false, length = 40)
    private String status;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Lob
    @Column(name = "last_error")
    private String lastError;

    @Lob
    @Column(name = "prompt_text")
    private String promptText;

    @Lob
    @Column(name = "llm_request")
    private String llmRequest;

    @Lob
    @Column(name = "llm_response")
    private String llmResponse;

    @Lob
    @Column(name = "bitrix_update_response")
    private String bitrixUpdateResponse;

    protected LeadProcessingJob() {
    }

    public LeadProcessingJob(Long incomingEventId, String eventName, String portalDomain, String leadId, OffsetDateTime now) {
        this.incomingEventId = incomingEventId;
        this.eventName = eventName;
        this.portalDomain = portalDomain;
        this.leadId = leadId;
        this.status = "PENDING";
        this.attempt = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getIncomingEventId() {
        return incomingEventId;
    }

    public String getEventName() {
        return eventName;
    }

    public String getPortalDomain() {
        return portalDomain;
    }

    public String getLeadId() {
        return leadId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getPromptText() {
        return promptText;
    }

    public void setPromptText(String promptText) {
        this.promptText = promptText;
    }

    public String getLlmRequest() {
        return llmRequest;
    }

    public void setLlmRequest(String llmRequest) {
        this.llmRequest = llmRequest;
    }

    public String getLlmResponse() {
        return llmResponse;
    }

    public void setLlmResponse(String llmResponse) {
        this.llmResponse = llmResponse;
    }

    public String getBitrixUpdateResponse() {
        return bitrixUpdateResponse;
    }

    public void setBitrixUpdateResponse(String bitrixUpdateResponse) {
        this.bitrixUpdateResponse = bitrixUpdateResponse;
    }
}
