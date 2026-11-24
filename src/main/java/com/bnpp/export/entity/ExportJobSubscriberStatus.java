package com.bnpp.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** One subscriber's file in a send: its status, the file built, and where it was archived once delivered. */
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

    /** PENDING, IN_PROGRESS, COMPLETED or FAILED. */
    @Column(nullable = false)
    private String status;

    private int records;

    private long fileSizeBytes;

    @Column(length = 1000)
    private String filePath;

    @Column(length = 1000)
    private String archivePath;

    @Column(length = 4000)
    private String errorMessage;

    private int retryCount;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

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
