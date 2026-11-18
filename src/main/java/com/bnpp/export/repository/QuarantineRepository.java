package com.bnpp.export.repository;

import com.bnpp.export.model.QuarantineRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface QuarantineRepository extends JpaRepository<QuarantineRecord, Long> {

    List<QuarantineRecord> findAllByOrderByCreatedAtDesc();

    long countByPatchAppliedFalse();

    List<QuarantineRecord> findBySubscriberStatusIdAndPatchAppliedFalse(Long subscriberStatusId);

    @Transactional
    void deleteBySubscriberStatusId(Long subscriberStatusId);

    boolean existsBySubscriberId(Long subscriberId);
}
