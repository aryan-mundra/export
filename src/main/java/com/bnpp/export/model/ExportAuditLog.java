package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ver_export_audit")
public class ExportAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the entry is about: EXPORT_JOB, SUBSCRIBER, or TEMPLATE. */
    @Column(nullable = false)
    private String entityType;

    private Long entityId;

    private Long exportJobId;

    private Long subscriberId;

    @Column(nullable = false)
    private String action;

    @Column(length = 4000)
    private String details;

    /** Username that caused the change, or "system". */
    @Column(nullable = false)
    private String actor;

    @Column(name = "logged_at")
    private LocalDateTime timestamp;

    @PrePersist
    void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        if (entityType == null) {
            entityType = "EXPORT_JOB";
        }
        if (entityId == null && "EXPORT_JOB".equals(entityType)) {
            entityId = exportJobId;
        }
        if (actor == null) {
            actor = "system";
        }
    }
}
