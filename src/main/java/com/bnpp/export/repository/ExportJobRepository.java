package com.bnpp.export.repository;

import com.bnpp.export.model.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ExportJobRepository extends JpaRepository<ExportJob, Long> {

    List<ExportJob> findAllByOrderByCreatedAtDesc();

    List<ExportJob> findTop10ByOrderByCreatedAtDesc();

    List<ExportJob> findByVersionIdOrderByCreatedAtDesc(Long versionId);

    long countByStatus(String status);

    long countByStatusIn(Collection<String> statuses);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ExportJob j
               set j.completedSubscribers = :completed,
                   j.failedSubscribers = :failed,
                   j.heldSubscribers = :held,
                   j.skippedSubscribers = :skipped,
                   j.awaitingApprovals = :awaiting,
                   j.totalRecordsProcessed = :processed,
                   j.totalRecordsQuarantined = :quarantined
             where j.id = :id
            """)
    int updateCounters(@Param("id") Long id,
                       @Param("completed") int completed,
                       @Param("failed") int failed,
                       @Param("held") int held,
                       @Param("skipped") int skipped,
                       @Param("awaiting") int awaiting,
                       @Param("processed") long processed,
                       @Param("quarantined") long quarantined);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ExportJob j
               set j.status = :status,
                   j.completedAt = :completedAt,
                   j.errorMessage = :errorMessage
             where j.id = :id
               and j.status <> :status
            """)
    int markFinished(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("completedAt") LocalDateTime completedAt,
                     @Param("errorMessage") String errorMessage);
}
