package com.bnpp.export.service.impl;

import com.bnpp.export.entity.ExportAuditLog;
import com.bnpp.export.repository.ExportAuditLogRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.AuditService;
import org.springframework.stereotype.Service;

/** Implementation of {@link AuditService}. */
@Service
public class AuditServiceImpl implements AuditService {

    private final ExportAuditLogRepository repository;

    public AuditServiceImpl(ExportAuditLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void job(Long jobId, Long subscriberId, String action, String details) {
        write(EXPORT_JOB, jobId, jobId, subscriberId, action, details);
    }

    @Override
    public void subscriber(Long subscriberId, String action, String details) {
        write(SUBSCRIBER, subscriberId, null, subscriberId, action, details);
    }

    @Override
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
