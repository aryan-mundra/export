package com.bnpp.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ver_export_job")
public class ExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long versionId;

    private LocalDate fromDate;

    private LocalDate toDate;

    private String listCode;

    private String listName;

    private Long formatTemplateId;

    private Integer formatTemplateVersion;

    /** EXPORT: reads the current data. RESEND: sends a stored data version again, without reading the source. */
    private String mode;

    /** For a resend: the data version sent, and to whom (subscriber ids, comma-separated). */
    private Long snapshotId;

    private String snapshotLabel;

    private String snapshotTemplate;

    /** For a resend: CSV or XML for every file, or null for each subscriber's own format. */
    private String formatOverride;

    /** The subscribers it was limited to: "id" or, for a resend, "id:FORMAT", comma-separated. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
    private String subscriberIds;

    private String triggeredBy;

    @Column(nullable = false)
    private String status;

    private int totalSubscribers;

    private int completedSubscribers;

    private int failedSubscribers;

    private int heldSubscribers;

    private int skippedSubscribers;

    private long totalRecordsProcessed;

    private long totalRecordsQuarantined;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(length = 4000)
    private String errorMessage;

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
