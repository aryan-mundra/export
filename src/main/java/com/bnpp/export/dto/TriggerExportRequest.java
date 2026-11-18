package com.bnpp.export.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record TriggerExportRequest(
        @NotNull @Positive Long versionId,
        String triggeredBy,
        LocalDate fromDate,
        LocalDate toDate,
        String reviewToken
) {
}
