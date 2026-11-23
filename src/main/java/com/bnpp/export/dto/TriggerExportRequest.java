package com.bnpp.export.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record TriggerExportRequest(
        @NotNull @Positive Long versionId,
        String triggeredBy,
        LocalDate fromDate,
        LocalDate toDate,
        String reviewToken,
        /** Who receives it: null or empty means every active subscriber. */
        java.util.List<Long> subscriberIds
) {
    public TriggerExportRequest(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate, String reviewToken) {
        this(versionId, triggeredBy, fromDate, toDate, reviewToken, null);
    }
}
