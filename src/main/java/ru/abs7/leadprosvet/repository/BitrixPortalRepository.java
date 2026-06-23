package ru.abs7.leadprosvet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.abs7.leadprosvet.domain.BitrixPortal;

import java.util.Optional;

public interface BitrixPortalRepository extends JpaRepository<BitrixPortal, Long> {
    Optional<BitrixPortal> findByDomain(String domain);
    Optional<BitrixPortal> findByMemberId(String memberId);
    Optional<BitrixPortal> findFirstByInstalledTrueOrderByUpdatedAtDesc();
    Optional<BitrixPortal> findFirstByOrderByUpdatedAtDesc();
}
