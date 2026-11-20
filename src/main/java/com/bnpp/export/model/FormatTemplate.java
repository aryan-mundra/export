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
@Table(name = "ver_export_template")
public class FormatTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * All versions of one named template share a family id: the id of its first version.
     * Subscribers choose a template by this id and always get its approved version in use.
     */
    private Long familyId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String formatType;

    /** Version within the template (family), starting at 1. */
    private int versionNumber;

    /** The approved version in use for its template. At most one per family. */
    private boolean active;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private String createdByName;

    private String approvedBy;

    private String approvedByName;

    private LocalDateTime approvedAt;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (versionNumber <= 0) {
            versionNumber = 1;
        }
        if (status == null) {
            status = "PENDING";
        }
    }
}
