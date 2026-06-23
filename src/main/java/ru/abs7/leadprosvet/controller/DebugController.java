package ru.abs7.leadprosvet.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.leadprosvet.domain.BitrixPortal;
import ru.abs7.leadprosvet.domain.IncomingBitrixEvent;
import ru.abs7.leadprosvet.repository.BitrixPortalRepository;
import ru.abs7.leadprosvet.repository.IncomingBitrixEventRepository;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final BitrixPortalRepository bitrixPortalRepository;
    private final IncomingBitrixEventRepository incomingBitrixEventRepository;

    public DebugController(BitrixPortalRepository bitrixPortalRepository, IncomingBitrixEventRepository incomingBitrixEventRepository) {
        this.bitrixPortalRepository = bitrixPortalRepository;
        this.incomingBitrixEventRepository = incomingBitrixEventRepository;
    }

    @GetMapping("/portals")
    public ResponseEntity<List<Map<String, Object>>> portals() {
        return ResponseEntity.ok(bitrixPortalRepository.findAll().stream()
                .map(this::portalView)
                .toList());
    }

    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> events() {
        return ResponseEntity.ok(incomingBitrixEventRepository.findAllByOrderByIdDesc(PageRequest.of(0, 50)).stream()
                .map(this::eventView)
                .toList());
    }

    private Map<String, Object> portalView(BitrixPortal portal) {
        return Map.of(
                "id", portal.getId(),
                "domain", value(portal.getDomain()),
                "memberId", value(portal.getMemberId()),
                "installed", portal.isInstalled(),
                "hasAccessToken", portal.getAccessToken() != null && !portal.getAccessToken().isBlank(),
                "hasRefreshToken", portal.getRefreshToken() != null && !portal.getRefreshToken().isBlank(),
                "hasApplicationToken", portal.getApplicationToken() != null && !portal.getApplicationToken().isBlank(),
                "installedAt", value(portal.getInstalledAt()),
                "updatedAt", value(portal.getUpdatedAt())
        );
    }

    private Map<String, Object> eventView(IncomingBitrixEvent event) {
        return Map.of(
                "id", event.getId(),
                "eventName", value(event.getEventName()),
                "portalDomain", value(event.getPortalDomain()),
                "entityId", value(event.getEntityId()),
                "requestIp", value(event.getRequestIp()),
                "status", value(event.getStatus()),
                "createdAt", value(event.getCreatedAt())
        );
    }

    private Object value(Object value) {
        return value == null ? "" : value;
    }
}
