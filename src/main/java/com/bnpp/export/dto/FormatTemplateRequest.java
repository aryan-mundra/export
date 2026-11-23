package com.bnpp.export.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * A list (template): the gs_ columns and row filters only. It has no file format; the format is chosen
 * per subscriber when a version is sent.
 *
 * @param filters row filters (may be empty): only list entries that pass every one go in the file
 */
public record FormatTemplateRequest(
        @NotBlank String name,
        @NotEmpty @Valid List<FormatFieldRequest> fields,
        List<FormatFilterRequest> filters
) {
    public FormatTemplateRequest(String name, List<FormatFieldRequest> fields) {
        this(name, fields, List.of());
    }
}
