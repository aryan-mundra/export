package com.bnpp.export.service;

import com.bnpp.export.model.ExportAuditLog;
import com.bnpp.export.repository.ExportAuditLogRepository;
import com.bnpp.export.security.CurrentUser;
import org.springframework.stereotype.Service;

/** Every change the export module makes is written to ver_export_audit with the user who made it. */
@Service
public class AuditService {

    public static final String EXPORT_JOB = "EXPORT_JOB";
    public static final String SUBSCRIBER = "SUBSCRIBER";
    public static final String TEMPLATE = "TEMPLATE";

    private final ExportAuditLogRepository repository;

    public AuditService(ExportAuditLogRepository repository) {
        this.repository = repository;
    }

    public void job(Long jobId, Long subscriberId, String action, String details) {
        write(EXPORT_JOB, jobId, jobId, subscriberId, action, details);
    }

    public void subscriber(Long subscriberId, String action, String details) {
        write(SUBSCRIBER, subscriberId, null, subscriberId, action, details);
    }

    public void template(Long templateId, String action, String details) {
        write(TEMPLATE, templateId, null, null, action, details);
    }

    private void write(String entityType, Long entityId, Long jobId, Long subscriberId, String action, String details) {
        ExportAuditLog entry = new ExportAuditLog();
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setExportJobId(jobId);
        entry.setSubscriberId(subscriberId);
        entry.setAction(action);
        entry.setDetails(details == null || details.length() <= 3900 ? details : details.substring(0, 3900));
        entry.setActor(CurrentUser.actor());
        repository.save(entry);
    }
}
