package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

public record FormatFieldRequest(
        @NotBlank String sourceColumn,
        @NotBlank String headerName
) {
}
