package com.bnpp.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** A send of one data version of a list to chosen subscribers: one XML file each, delivered in the background. */
@Data
@Entity
@Table(name = "ver_export_job")
public class ExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The list (template family) and its name. */
    private Long familyId;

    private String listName;

    /** The data version sent (ver_export_data_version.id) and its number within the list. */
    @Column(nullable = false)
    private Long dataVersionId;

    private int dataVersionNumber;

    /** The list version the data version was read with. */
    private Long formatTemplateId;

    private Integer formatTemplateVersion;

    /** The subscribers it is sent to, comma-separated ids. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String subscriberIds;

    private String triggeredBy;

    /** PENDING, IN_PROGRESS, COMPLETED or FAILED. */
    @Column(nullable = false)
    private String status;

    private int totalSubscribers;

    private int completedSubscribers;

    private int failedSubscribers;

    /** Records sent, over every subscriber. */
    private long totalRecords;

    @Column(length = 4000)
    private String errorMessage;

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
