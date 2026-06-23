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
@Table(name = "incoming_bitrix_events")
public class IncomingBitrixEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_name", length = 120)
    private String eventName;

    @Column(name = "portal_domain", length = 255)
    private String portalDomain;

    @Column(name = "entity_id", length = 80)
    private String entityId;

    @Column(name = "request_ip", length = 80)
    private String requestIp;

    @Column(name = "status", nullable = false, length = 40)
    private String status;

    @Lob
    @Column(name = "raw_params")
    private String rawParams;

    @Lob
    @Column(name = "raw_body")
    private String rawBody;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected IncomingBitrixEvent() {
    }

    public IncomingBitrixEvent(String eventName, String portalDomain, String entityId, String requestIp, String status, String rawParams, String rawBody, OffsetDateTime createdAt) {
        this.eventName = eventName;
        this.portalDomain = portalDomain;
        this.entityId = entityId;
        this.requestIp = requestIp;
        this.status = status;
        this.rawParams = rawParams;
        this.rawBody = rawBody;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventName() {
        return eventName;
    }

    public String getPortalDomain() {
        return portalDomain;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getRequestIp() {
        return requestIp;
    }

    public String getStatus() {
        return status;
    }

    public String getRawParams() {
        return rawParams;
    }

    public String getRawBody() {
        return rawBody;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
