package ru.abs7.leadprosvet.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.leadprosvet.domain.BitrixPortal;
import ru.abs7.leadprosvet.repository.BitrixPortalRepository;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class BitrixInstallService {

    private final BitrixPortalRepository bitrixPortalRepository;
    private final PayloadParser payloadParser;

    public BitrixInstallService(BitrixPortalRepository bitrixPortalRepository, PayloadParser payloadParser) {
        this.bitrixPortalRepository = bitrixPortalRepository;
        this.payloadParser = payloadParser;
    }

    @Transactional
    public BitrixPortal saveInstallPayload(Map<String, String> params, String body) {
        Map<String, String> data = payloadParser.mergeParamsAndBody(params, body);

        String domain = payloadParser.getAny(data, "DOMAIN", "domain", "auth[domain]", "auth[client_endpoint]");
        String memberId = payloadParser.getAny(data, "member_id", "MEMBER_ID", "auth[member_id]");

        BitrixPortal portal = findExisting(domain, memberId);
        OffsetDateTime now = OffsetDateTime.now();

        portal.setDomain(normalizeDomain(domain));
        portal.setMemberId(memberId);
        portal.setClientEndpoint(payloadParser.getAny(data, "client_endpoint", "CLIENT_ENDPOINT", "auth[client_endpoint]"));
        portal.setServerEndpoint(payloadParser.getAny(data, "server_endpoint", "SERVER_ENDPOINT", "auth[server_endpoint]"));
        portal.setAccessToken(payloadParser.getAny(data, "access_token", "AUTH_ID", "auth[access_token]", "auth[AUTH_ID]"));
        portal.setRefreshToken(payloadParser.getAny(data, "refresh_token", "REFRESH_ID", "auth[refresh_token]", "auth[REFRESH_ID]"));
        portal.setApplicationToken(payloadParser.getAny(data, "application_token", "APP_SID", "auth[application_token]", "auth[APP_SID]"));
        portal.setInstalled(true);
        if (portal.getInstalledAt() == null) {
            portal.setInstalledAt(now);
        }
        portal.setUpdatedAt(now);
        return bitrixPortalRepository.save(portal);
    }

    private BitrixPortal findExisting(String domain, String memberId) {
        if (memberId != null && !memberId.isBlank()) {
            return bitrixPortalRepository.findByMemberId(memberId).orElseGet(BitrixPortal::new);
        }
        String normalizedDomain = normalizeDomain(domain);
        if (normalizedDomain != null && !normalizedDomain.isBlank()) {
            return bitrixPortalRepository.findByDomain(normalizedDomain).orElseGet(BitrixPortal::new);
        }
        return new BitrixPortal();
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
