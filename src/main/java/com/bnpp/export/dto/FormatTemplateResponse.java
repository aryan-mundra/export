package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

public record FormatTemplateResponse(
        Long id,
        /** The template this version belongs to; subscribers choose a template by this id. */
        Long familyId,
        String name,
        String formatType,
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
        /** Subscribers that use this template. */
        List<String> usedBy
) {
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
