package com.bnpp.export.service;

/** Every change the module makes is written to ver_export_audit with the user who made it. */
public interface AuditService {

    String EXPORT_JOB = "EXPORT_JOB";
    String SUBSCRIBER = "SUBSCRIBER";
    String TEMPLATE = "TEMPLATE";

    void job(Long jobId, Long subscriberId, String action, String details);

    void subscriber(Long subscriberId, String action, String details);

    void template(Long templateId, String action, String details);
}
