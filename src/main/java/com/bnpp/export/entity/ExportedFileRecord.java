package com.bnpp.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Which list records went into which file, in file order. Rows are written in batches by
 * {@link com.bnpp.export.service.ExportManifestService}; the entity defines the table.
 */
@Data
@Entity
@Table(name = "ver_export_file_record", indexes = {
        @Index(name = "ix_ver_export_file_record_file", columnList = "fileId"),
        @Index(name = "ix_ver_export_file_record_record", columnList = "recordId")
})
public class ExportedFileRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long fileId;

    @Column(nullable = false)
    private String recordId;

    /** ADD, MODIFY, or DELETE as it appeared in the file. */
    private String action;

    /** 1-based line of the record in the file. */
    private int lineNumber;
}
