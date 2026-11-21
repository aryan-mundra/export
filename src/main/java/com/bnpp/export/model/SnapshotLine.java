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
/** Line {@code lineNo} of a data version holds the row stored under {@code rowKey}. */
@Data
@Entity
@IdClass(SnapshotLineId.class)
@Table(name = "ver_export_snapshot_row",
        indexes = {
                @Index(name = "ix_snapshot_row_record", columnList = "snapshot_id, record_id"),
                @Index(name = "ix_snapshot_row_key", columnList = "row_key")
        })
public class SnapshotLine {

    @Id
    @Column(name = "snapshot_id")
    private Long snapshotId;

    @Id
    @Column(name = "line_no")
    private Long lineNo;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "row_key", nullable = false, length = 64)
    private String rowKey;
}
