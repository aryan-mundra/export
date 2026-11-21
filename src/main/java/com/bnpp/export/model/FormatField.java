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

    /** The gs_ table the column comes from. */
    private String sourceTable;

    @Column(nullable = false)
    private String sourceColumn;

    /** The gs_ table's BNP_ID column, which matches its rows to a list entry. */
    private String joinColumn;

    /** The list entry field that joinColumn must equal: its BNP_ID. */
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
