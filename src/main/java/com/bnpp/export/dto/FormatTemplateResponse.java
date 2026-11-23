package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

public record FormatTemplateResponse(
        Long id,
        /** The template this version belongs to; subscribers choose a template by this id. */
        Long familyId,
        String name,
        int versionNumber,
        boolean active,
        String status,
        String createdBy,
        String createdByName,
        String approvedBy,
        String approvedByName,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        List<FieldResponse> fields,
        /** Row filters: only list entries that pass every one go in the file. Empty means every entry. */
        List<FilterResponse> filters,
        /** Subscribers that use this template. */
        List<String> usedBy,
        String reviewedBy,
        String reviewedByName,
        LocalDateTime reviewedAt,
        /** The approver's comment on this version (reason for rejection or requested changes). */
        String reviewComment
) {
    /** @param summary the filter in words, e.g. "gs_address.city is Paris, London" */
    public record FilterResponse(
            String sourceTable,
            String sourceColumn,
            String op,
            List<String> values,
            boolean blank,
            String summary
    ) {
    }

    public record FieldResponse(
            Long id,
            String sourceTable,
            String sourceColumn,
            String headerName,
            String description,
            int position,
            String joinColumn,
            String joinField
    ) {
    }
}
