package com.bnpp.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** One column of a list version: a column of a group table, and its name in the file. */
@Data
@Entity
@Table(name = "ver_export_template_field")
public class FormatField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateId;

    /** The group table the column comes from. */
    @Column(nullable = false)
    private String sourceTable;

    @Column(nullable = false)
    private String sourceColumn;

    /** How the table joins its group: its key (root table) or its foreign key column... */
    private String joinColumn;

    /** ...and the "table.column" that column points to, as written in the groups file when the list was saved. */
    private String joinTarget;

    @Column(nullable = false)
    private String headerName;

    private int position;
}
