package ru.abs7.leadprosvet.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.leadprosvet.domain.IncomingBitrixEvent;
import ru.abs7.leadprosvet.repository.IncomingBitrixEventRepository;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class BitrixEventLogService {

    private final IncomingBitrixEventRepository eventRepository;
    private final PayloadParser payloadParser;
    private final ObjectMapper objectMapper;

    public BitrixEventLogService(IncomingBitrixEventRepository eventRepository, PayloadParser payloadParser, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.payloadParser = payloadParser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IncomingBitrixEvent saveIncomingEvent(Map<String, String> params, String body, String requestIp) {
        Map<String, String> data = payloadParser.mergeParamsAndBody(params, body);
        String eventName = payloadParser.getAny(data, "event", "EVENT", "eventName");
        String domain = payloadParser.getAny(data, "auth[domain]", "domain", "DOMAIN", "auth[client_endpoint]");
        String entityId = payloadParser.getAny(data, "data[FIELDS][ID]", "FIELDS[ID]", "ID", "leadId", "entityId");

        IncomingBitrixEvent event = new IncomingBitrixEvent(
                emptyToNull(eventName),
                normalizeDomain(domain),
                emptyToNull(entityId),
                requestIp,
                "accepted",
                toJson(params == null ? Map.of() : params),
                body == null ? "" : body,
                OffsetDateTime.now()
        );
        return eventRepository.save(event);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        value = value.replace("https://", "").replace("http://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        return value;
    }
}
