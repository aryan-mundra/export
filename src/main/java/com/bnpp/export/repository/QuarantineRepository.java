package com.bnpp.export.repository;

import com.bnpp.export.entity.QuarantineRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface QuarantineRepository extends JpaRepository<QuarantineRecord, Long> {

    /** A page of all held records, newest first (the id breaks ties, so pages never overlap). */
    Page<QuarantineRecord> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    /**
     * Held records whose record id, reason or version contains the text, or that belong to one of the given
     * subscribers (those whose name matched), newest first. {@code like} as in {@link ExportJobRepository#search}.
     */
    @Query(value = """
            select q from QuarantineRecord q
             where lower(q.recordIdentifier) like :like escape '\\'
                or lower(q.quarantineReason) like :like escape '\\'
                or str(q.versionId) like :like escape '\\'
                or q.subscriberId in :subscriberIds
             order by q.createdAt desc, q.id desc
            """, countQuery = """
            select count(q) from QuarantineRecord q
             where lower(q.recordIdentifier) like :like escape '\\'
                or lower(q.quarantineReason) like :like escape '\\'
                or str(q.versionId) like :like escape '\\'
                or q.subscriberId in :subscriberIds
            """)
    Page<QuarantineRecord> search(@Param("like") String like, @Param("subscriberIds") Collection<Long> subscriberIds,
                                  Pageable page);

    long countByPatchAppliedFalse();

    List<QuarantineRecord> findBySubscriberStatusIdAndPatchAppliedFalse(Long subscriberStatusId);

    @Transactional
    void deleteBySubscriberStatusId(Long subscriberStatusId);

    boolean existsBySubscriberId(Long subscriberId);
}
