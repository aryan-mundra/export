package com.bnpp.export.repository;

import com.bnpp.export.entity.ExportJobSubscriberStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ExportJobSubscriberStatusRepository extends JpaRepository<ExportJobSubscriberStatus, Long> {

    List<ExportJobSubscriberStatus> findByExportJobIdOrderByIdAsc(Long exportJobId);

    long countByStatusIn(Collection<String> statuses);

    boolean existsBySubscriberId(Long subscriberId);
}
