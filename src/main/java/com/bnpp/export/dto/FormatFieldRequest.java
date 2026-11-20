package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One export column. sourceTable is a gs_ table name, or empty for a built-in list record column.
 * For a gs_ column, joinColumn (a column of that table) and joinField (a list record field) say how
 * the table's rows are matched to each record; leave them empty to use RECORD_ID/ID = record id.
 */
public record FormatFieldRequest(
        String sourceTable,
        @NotBlank String sourceColumn,
        @NotBlank String headerName,
        String joinColumn,
        String joinField
) {
    public FormatFieldRequest(String sourceColumn, String headerName) {
        this(null, sourceColumn, headerName, null, null);
    }

    public FormatFieldRequest(String sourceTable, String sourceColumn, String headerName) {
        this(sourceTable, sourceColumn, headerName, null, null);
    }
}
