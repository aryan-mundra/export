package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** One row per file the export builds for a subscriber, whether or not it was sent. */
@Data
@Entity
@Table(name = "ver_export_file", indexes = {
        @Index(name = "ix_ver_export_file_job", columnList = "exportJobId"),
        @Index(name = "ix_ver_export_file_subscriber", columnList = "subscriberId")
})
public class ExportedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long exportJobId;

    /** The ver_export_job_subscriber row this file belongs to. */
    @Column(nullable = false)
    private Long jobSubscriberId;

    @Column(nullable = false)
    private Long subscriberId;

    @Column(nullable = false)
    private Long versionId;

    @Column(nullable = false)
    private String formatType;

    private String exportMode;

    private Long formatTemplateId;

    private Integer formatTemplateVersion;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false, length = 1000)
    private String filePath;

    private long fileSize;

    /** SHA-256 of the file content, hex encoded. Empty while the file is BUILDING. */
    @Column(length = 64)
    private String fileHash;

    private int recordCount;

    /** BUILDING while streaming, BUILT when complete, SENT once delivered, FAILED if not completed. */
    @Column(nullable = false)
    private String status;

    @Column(length = 1000)
    private String archivePath;

    private LocalDateTime sentAt;

    private String createdBy;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "BUILT";
        }
    }
}
