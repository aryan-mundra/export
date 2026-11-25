package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * One column of a list: a column of a group table, its name in the file (the column name when blank), and optionally the
 * values to keep (a record whose column holds another value is left out).
 */
public record FormatFieldRequest(@NotBlank String sourceTable, @NotBlank String sourceColumn, String headerName, List<String> filterValues) {
}
