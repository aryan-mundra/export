package com.bnpp.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One template and list pair: the catalog row of its data versions.
 *
 * It is locked while a new version is numbered, and it carries the pair's summary (latest version,
 * counts, last use), kept up to date in the same transaction. Screens list and search these rows
 * instead of scanning every version, so the catalog stays fast with thousands of lists.
 */
@Data
@Entity
@Table(name = "ver_export_snapshot_series",
        uniqueConstraints = @UniqueConstraint(name = "uq_snapshot_series", columnNames = {"family_id", "list_code"}),
        indexes = {
                @Index(name = "ix_snapshot_series_used", columnList = "last_used_at"),
                @Index(name = "ix_snapshot_series_name", columnList = "template_name, list_name")
        })
public class SnapshotSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "list_code", nullable = false)
    private String listCode;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "list_name")
    private String listName;

    @Column(name = "version_count")
    private Integer versionCount;

    @Column(name = "latest_snapshot_id")
    private Long latestSnapshotId;

    @Column(name = "latest_label")
    private String latestLabel;

    @Column(name = "latest_at")
    private LocalDateTime latestAt;

    /** Exports that produced or reused one of its versions. */
    @Column(name = "exports_served")
    private Long exportsServed;

    @Column(name = "total_rows")
    private Long totalRows;

    /** Rows actually stored for all its versions; the rest are shared between versions. */
    @Column(name = "stored_rows")
    private Long storedRows;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
