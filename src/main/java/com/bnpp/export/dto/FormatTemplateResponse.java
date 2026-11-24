package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A list version: its columns, approval status and the subscribers of the list.
 *
 * @param familyId the list this version belongs to; subscribers choose a list by this id
 * @param active   whether it is the approved version in use
 * @param status   PENDING, APPROVED, CHANGES_REQUESTED, REJECTED or SUPERSEDED
 * @param usedBy   the subscribers of the list
 */
public record FormatTemplateResponse(Long id, Long familyId, String name, int versionNumber, boolean active, String status,
                                     String createdBy, String createdByName, String approvedBy, String approvedByName,
                                     LocalDateTime approvedAt, LocalDateTime createdAt, List<FieldResponse> fields, List<String> usedBy,
                                     String reviewedBy, String reviewedByName, LocalDateTime reviewedAt, String reviewComment) {

    /** @param joinTarget the "table.column" the column's table joins to (its own key on the root table) */
    public record FieldResponse(Long id, String sourceTable, String sourceColumn, String headerName, int position,
                                String joinColumn, String joinTarget) {
    }
}
