package ru.abs7.leadprosvet.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.leadprosvet.domain.LeadProcessingJob;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LeadProcessingJobRepository extends JpaRepository<LeadProcessingJob, Long> {

    List<LeadProcessingJob> findAllByStatusOrderByIdAsc(String status, Pageable pageable);

    List<LeadProcessingJob> findAllByStatusInOrderByIdAsc(Collection<String> statuses, Pageable pageable);

    List<LeadProcessingJob> findAllByOrderByIdDesc(Pageable pageable);

    long countByStatus(String status);

    boolean existsByLeadIdAndStatusIn(String leadId, Collection<String> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LeadProcessingJob j
            set j.status = 'PROCESSING',
                j.attempt = 0,
                j.startedAt = :now,
                j.finishedAt = null,
                j.updatedAt = :now,
                j.lastError = null
            where j.id = :id
              and j.status = 'PENDING'
            """)
    int claimPendingJob(@Param("id") Long id, @Param("now") OffsetDateTime now);

    @Query("""
            select count(j) > 0
            from LeadProcessingJob j
            where j.leadId = :leadId
              and j.status = 'DONE'
              and j.finishedAt is not null
              and j.finishedAt >= :after
            """)
    boolean existsRecentDoneByLeadId(@Param("leadId") String leadId, @Param("after") OffsetDateTime after);

    default Optional<LeadProcessingJob> firstPending() {
        List<LeadProcessingJob> jobs = findAllByStatusOrderByIdAsc("PENDING", Pageable.ofSize(1));
        if (jobs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jobs.getFirst());
    }
}
