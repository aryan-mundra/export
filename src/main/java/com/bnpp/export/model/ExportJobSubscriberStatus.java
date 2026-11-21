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
@Table(name = "ver_export_job_subscriber")
public class ExportJobSubscriberStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long exportJobId;

    private Long subscriberId;

    private String subscriberName;

    private String formatType;

    @Column(nullable = false)
    private String status;

    private int recordsProcessed;

    private int recordsQuarantined;

    private long fileSizeBytes;

    @Column(length = 1000)
    private String filePath;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(length = 4000)
    private String errorMessage;

    private int retryCount;

    private Long formatTemplateId;

    private Integer formatTemplateVersion;

    /** The data version (snapshot) this file was built from. A retry rebuilds it from the same one. */
    private Long snapshotId;

    private String snapshotLabel;

    /** CREATED: this export produced a new data version. REUSED: the data was identical to an existing one. */
    private String snapshotOutcome;

    @Column(length = 1000)
    private String archivePath;

    private String acceptedBy;

    private LocalDateTime acceptedAt;

    @Column(length = 1000)
    private String holdReason;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }
}
