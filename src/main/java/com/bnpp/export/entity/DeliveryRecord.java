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

@Data
@Entity
@Table(name = "ver_export_delivery")
public class DeliveryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long versionId;

    private Long subscriberId;

    private String formatType;

    private String exportType;

    @Column(length = 1000)
    private String filePath;

    private String fileHash;

    private int recordCount;

    @Column(nullable = false)
    private String status;

    private LocalDateTime deliveredAt;

    private String cftTrackingId;

    private Long formatTemplateId;

    private Integer formatTemplateVersion;

    @Column(length = 1000)
    private String archivePath;

    @Column(length = 1000)
    private String quarantineNote;

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
