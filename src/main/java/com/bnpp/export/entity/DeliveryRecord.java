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

/** One delivery attempt of a file to a subscriber, with the file's hash (the proof) and its archived copy. */
@Data
@Entity
@Table(name = "ver_export_delivery")
public class DeliveryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long dataVersionId;

    private Long subscriberId;

    @Column(length = 1000)
    private String filePath;

    private String fileHash;

    private int recordCount;

    /** PENDING, SUCCESS or FAILED. */
    @Column(nullable = false)
    private String status;

    private LocalDateTime deliveredAt;

    private String cftTrackingId;

    @Column(length = 1000)
    private String archivePath;

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
