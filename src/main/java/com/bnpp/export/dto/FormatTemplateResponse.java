package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

public record FormatTemplateResponse(
        Long id,
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
        List<String> usedBy
) {
    public record FieldResponse(
            Long id,
            String sourceTable,
            String sourceColumn,
            String headerName,
            String description,
            int position
    ) {
    }
}
