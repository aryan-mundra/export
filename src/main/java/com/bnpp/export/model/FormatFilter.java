package com.bnpp.export.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row filter on a template version: which list entries go in the file. It tests one gs_ column
 * of the template, the way the cell shows it: a list entry passes when any of its values in that
 * column matches. A template's filters must all pass (AND).
 */
@Data
@Entity
@Table(name = "ver_export_template_filter")
public class FormatFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false)
    private String sourceTable;

    @Column(nullable = false)
    private String sourceColumn;

    /** in, not_in, contains, eq, ne, gt, gte, lt, lte, between, empty, not_empty. */
    @Column(name = "filter_op", nullable = false, length = 20)
    private String operator;

    /** The values, as a JSON array of strings. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "filter_values")
    private String valuesJson;

    /** in: also keep entries whose cell is empty. not_in: also leave out entries whose cell is empty. */
    private boolean includeBlank;

    private int position;
}
