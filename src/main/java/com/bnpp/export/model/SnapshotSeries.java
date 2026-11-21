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

/** One row per line of data versions (template and list), locked while a version is numbered. */
@Data
@Entity
@Table(name = "ver_export_snapshot_series",
        uniqueConstraints = @UniqueConstraint(name = "uq_snapshot_series", columnNames = {"family_id", "list_code"}))
public class SnapshotSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "list_code", nullable = false)
    private String listCode;
}
