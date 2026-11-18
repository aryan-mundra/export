package com.bnpp.export.repository;

import com.bnpp.export.model.ExportAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportAuditLogRepository extends JpaRepository<ExportAuditLog, Long> {

    List<ExportAuditLog> findByExportJobIdOrderByTimestampAsc(Long exportJobId);
}
