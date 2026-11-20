package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Locale;

@Data
@Entity
@Table(name = "ver_export_template_field")
public class FormatField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateId;

    /** A gs_ table name, or null for a built-in list record column. */
    private String sourceTable;

    @Column(nullable = false)
    private String sourceColumn;

    /**
     * For a gs_ column: the column of the gs_ table that links its rows to a list record.
     * Null means the table's RECORD_ID or ID column.
     */
    private String joinColumn;

    /** For a gs_ column: the list record field that joinColumn must equal. Null means the record id. */
    private String joinField;

    @Column(nullable = false)
    private String headerName;

    private int position;

    public boolean isGoldenSource() {
        return sourceTable != null && !sourceTable.isBlank();
    }

    /** The key that holds this field's value in an export record. */
    public String recordKey() {
        if (!isGoldenSource()) {
            return sourceColumn;
        }
        return sourceTable.toLowerCase(Locale.ROOT) + "." + sourceColumn.toLowerCase(Locale.ROOT);
    }
}
