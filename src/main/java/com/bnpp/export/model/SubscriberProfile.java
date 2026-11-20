package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ver_export_subscriber")
public class SubscriberProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String formatType;

    @Column(nullable = false)
    private String scope;

    @Column(nullable = false)
    private String exportMode;

    private String businessLine;

    private String region;

    private String cftEndpoint;

    private String endpointStatus = "APPROVED";

    private boolean active = true;

    @jakarta.persistence.Transient
    private String lastDeliveryStatus;

    @jakarta.persistence.Transient
    private String lastFileName;

    @jakarta.persistence.Transient
    private java.time.LocalDateTime lastDeliveredAt;

    private int maxBatchSize = 10_000;

    private int exportTimeoutMinutes = 60;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (maxBatchSize <= 0) {
            maxBatchSize = 10_000;
        }
        if (exportTimeoutMinutes <= 0) {
            exportTimeoutMinutes = 60;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
