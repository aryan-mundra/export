package com.bnpp.export.repository;

import com.bnpp.export.entity.ExportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExportJobRepository extends JpaRepository<ExportJob, Long> {

    /** A page of all jobs, newest first (the id breaks ties, so pages never overlap). */
    Page<ExportJob> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    List<ExportJob> findTop10ByOrderByCreatedAtDesc();

    /**
     * A page of the jobs whose version, user, status or list name contains the text, newest first. {@code like}
     * is {@code %text%}, lower case, with %, _ and \ escaped by a backslash.
     */
    @Query(value = """
            select j from ExportJob j
             where str(j.versionId) like :like escape '\\'
                or lower(j.triggeredBy) like :like escape '\\'
                or lower(j.status) like :like escape '\\'
                or lower(j.listName) like :like escape '\\'
             order by j.createdAt desc, j.id desc
            """, countQuery = """
            select count(j) from ExportJob j
             where str(j.versionId) like :like escape '\\'
                or lower(j.triggeredBy) like :like escape '\\'
                or lower(j.status) like :like escape '\\'
                or lower(j.listName) like :like escape '\\'
            """)
    Page<ExportJob> search(@Param("like") String like, Pageable page);

    Optional<ExportJob> findFirstByVersionIdOrderByCreatedAtDesc(Long versionId);

    List<ExportJob> findByVersionIdOrderByCreatedAtDesc(Long versionId);

    long countByStatus(String status);

    long countByStatusIn(Collection<String> statuses);

    List<ExportJob> findByStatusIn(Collection<String> statuses);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ExportJob j
               set j.completedSubscribers = :completed,
                   j.failedSubscribers = :failed,
                   j.heldSubscribers = :held,
                   j.skippedSubscribers = :skipped,
                   j.totalRecordsProcessed = :processed,
                   j.totalRecordsQuarantined = :quarantined
             where j.id = :id
            """)
    int updateCounters(@Param("id") Long id,
                       @Param("completed") int completed,
                       @Param("failed") int failed,
                       @Param("held") int held,
                       @Param("skipped") int skipped,
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
