package com.bnpp.export.entity;

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

    /** The template (family id) this subscriber's files are built with. */
    private Long templateFamilyId;

    private String endpointStatus = "APPROVED";

    private boolean active = true;

    private int maxBatchSize = 10_000;

    private int exportTimeoutMinutes = 60;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Whether a record goes to this subscriber: its business line and region, when set, must match the
     * record's (records without those fields are kept).
     */
    public boolean receives(java.util.Map<String, Object> record) {
        if (org.apache.commons.lang3.StringUtils.isNotBlank(businessLine) && record.containsKey("businessLine")
                && !businessLine.equalsIgnoreCase(String.valueOf(record.get("businessLine")))) {
            return false;
        }
        return org.apache.commons.lang3.StringUtils.isBlank(region)
                || !record.containsKey("region")
                || region.equalsIgnoreCase(String.valueOf(record.get("region")));
    }

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
