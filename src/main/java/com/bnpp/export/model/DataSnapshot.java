package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One data version: the exact rows a template produced from a list, frozen when it was first exported.
 *
 * Numbered {@code templateVersion.revision}: the first number is the approved template version that
 * built it (its columns and row filters), the second counts data changes under that template. So a
 * change of configuration (2.0) can never be mistaken for a change of data (1.3).
 *
 * The rows live in ver_export_snapshot_row / ver_export_snapshot_data. Nothing here is ever updated
 * except the usage counters, and nothing is ever deleted.
 */
@Data
@Entity
@Table(name = "ver_export_snapshot",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_snapshot_number", columnNames = {"family_id", "list_code", "version_major", "version_minor"}),
                @UniqueConstraint(name = "uq_snapshot_dataset", columnNames = {"family_id", "list_code", "template_id", "fingerprint"})
        },
        indexes = @Index(name = "ix_snapshot_series", columnList = "family_id, list_code"))
public class DataSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The template (all its versions) and the list: together they form one line of data versions. */
    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "list_code", nullable = false)
    private String listCode;

    private String listName;

    /** The template version (columns and row filters) that built these rows. */
    @Column(name = "template_id", nullable = false)
    private Long templateId;

    private String templateName;

    private String formatType;

    @Column(name = "version_major", nullable = false)
    private int major;

    @Column(name = "version_minor", nullable = false)
    private int minor;

    /** major.minor, e.g. 1.2 */
    @Column(nullable = false)
    private String label;

    /** The certified list version the rows were read from. */
    private Long sourceVersionId;

    /** SHA-256 of the column layout and every row, in record order. Equal fingerprints mean equal data. */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    private long rowCount;

    /** Rows this version added to storage; the others are shared with earlier versions. */
    private long storedRows;

    /** The column layout: header, gs_ table and column of each file column, in order (JSON). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String columnsJson;

    /** The data version this one was compared with when it was created (the newest before it), and the result. */
    private Long previousSnapshotId;

    private String previousLabel;

    private Long insertedRows;

    private Long deletedRows;

    private Long changedRows;

    private LocalDateTime createdAt;

    private String createdBy;

    private String createdByName;

    /** The export job that first produced this data. */
    private Long createdJobId;

    /** Later exports that found exactly this data again and reused it. */
    private int reuseCount;

    private LocalDateTime lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastUsedAt == null) {
            lastUsedAt = createdAt;
        }
    }
}
