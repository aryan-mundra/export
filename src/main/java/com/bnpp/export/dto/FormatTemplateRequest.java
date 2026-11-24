package com.bnpp.export.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** A list to save: its name and columns. The key of each group used is added when missing. */
public record FormatTemplateRequest(@NotBlank String name, @NotEmpty @Valid List<FormatFieldRequest> fields) {
}
