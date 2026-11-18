package com.bnpp.export.model;

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
@Table(name = "export_jobs")
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

    private String triggeredBy;

    @Column(nullable = false)
    private String status;

    private int totalSubscribers;

    private int completedSubscribers;

    private int failedSubscribers;

    private int heldSubscribers;

    private int skippedSubscribers;

    private int awaitingApprovals;

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
