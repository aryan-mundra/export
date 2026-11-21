package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One export column: a column of a gs_ table and its name in the file. Rows of the gs_ table are
 * matched to each list entry on BNP_ID; joinColumn and joinField are filled in by the server
 * (values sent by a client are ignored).
 */
public record FormatFieldRequest(
        String sourceTable,
        @NotBlank String sourceColumn,
        @NotBlank String headerName,
        String joinColumn,
        String joinField
) {
    public FormatFieldRequest(String sourceTable, String sourceColumn, String headerName) {
        this(sourceTable, sourceColumn, headerName, null, null);
    }
}
