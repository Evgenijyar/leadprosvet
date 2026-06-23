package ru.abs7.leadprosvet.service.bitrix;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.leadprosvet.domain.BitrixPortal;
import ru.abs7.leadprosvet.repository.BitrixPortalRepository;

@Service
public class BitrixPortalService {

    private final BitrixPortalRepository bitrixPortalRepository;

    public BitrixPortalService(BitrixPortalRepository bitrixPortalRepository) {
        this.bitrixPortalRepository = bitrixPortalRepository;
    }

    @Transactional(readOnly = true)
    public BitrixPortal currentPortalOrThrow() {
        return bitrixPortalRepository.findFirstByInstalledTrueOrderByUpdatedAtDesc()
                .or(() -> bitrixPortalRepository.findFirstByOrderByUpdatedAtDesc())
                .orElseThrow(() -> new BitrixRestException("Bitrix portal is not installed yet"));
    }
}
