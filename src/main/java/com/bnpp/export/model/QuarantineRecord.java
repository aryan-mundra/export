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
@Table(name = "ver_export_quarantine")
public class QuarantineRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long versionId;

    private Long exportJobId;

    private Long subscriberStatusId;

    private Long subscriberId;

    private String acceptedBy;

    private LocalDateTime acceptedAt;

    private String recordIdentifier;

    private String targetTable;

    @Column(nullable = false, length = 4000)
    private String quarantineReason;

    private boolean patchApplied = false;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
