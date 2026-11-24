package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

/** One column of a list: a column of a group table, and its name in the file (the column name when blank). */
public record FormatFieldRequest(@NotBlank String sourceTable, @NotBlank String sourceColumn, String headerName) {
}
