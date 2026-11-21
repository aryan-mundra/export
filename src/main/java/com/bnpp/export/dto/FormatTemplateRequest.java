package com.bnpp.export.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** @param filters row filters (may be empty): only list entries that pass every one go in the file */
public record FormatTemplateRequest(
        @NotBlank String name,
        @NotBlank String formatType,
        @NotEmpty @Valid List<FormatFieldRequest> fields,
        List<FormatFilterRequest> filters
) {
    public FormatTemplateRequest(String name, String formatType, List<FormatFieldRequest> fields) {
        this(name, formatType, fields, List.of());
    }
}
