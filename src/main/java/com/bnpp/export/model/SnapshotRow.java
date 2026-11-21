package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.LocalDate;

/*
 * Snapshot row tables. Rows are written and read with plain SQL in batches (SnapshotService); the
 * entity only declares the table, so H2 and PostgreSQL get the same schema from Hibernate.
 */
/**
 * One distinct row: a list entry with its template values. Stored once and shared by every data
 * version that holds exactly the same row, so a new version only adds the rows that changed.
 * The key is the SHA-256 of the row's content (and its template and list).
 */
@Data
@Entity
@Table(name = "ver_export_snapshot_data")
public class SnapshotRow {

    @Id
    @Column(name = "row_key", length = 64)
    private String rowKey;

    private Long recordId;

    private String bnpId;

    private String name;

    private String country;

    private LocalDate recordDate;

    private String action;

    private String riskRating;

    private String businessLine;

    private String region;

    private LocalDate changedOn;

    private boolean onSanctions;

    private boolean onWorldcheck;

    private boolean onPfa;

    /** The template's values, as a JSON array of strings (null for an empty cell). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String cells;
}
