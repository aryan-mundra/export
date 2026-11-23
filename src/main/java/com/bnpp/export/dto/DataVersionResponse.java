package com.bnpp.export.dto;

import com.bnpp.export.entity.DataSnapshot;

import java.time.LocalDateTime;

/**
 * A data version (1.1, 1.2, ...) of a list: its numbers, its table and how it changed. The rows themselves
 * are read page by page from {@code /api/data-versions/{id}/rows}.
 *
 * @param tableName the table holding its rows, e.g. ver_list_apac_v1_1
 */
public record DataVersionResponse(
        Long id,
        Long familyId,
        String listCode,
        String listName,
        Long templateId,
        String templateName,
        int major,
        int minor,
        String label,
        String tableName,
        Long sourceVersionId,
        String fingerprint,
        long rowCount,
        long storedRows,
        Long previousSnapshotId,
        String previousLabel,
        Long insertedRows,
        Long deletedRows,
        Long changedRows,
        LocalDateTime createdAt,
        String createdBy,
        String createdByName,
        Long createdJobId,
        int reuseCount,
        LocalDateTime lastUsedAt
) {
    public static DataVersionResponse from(DataSnapshot snapshot) {
        return new DataVersionResponse(snapshot.getId(), snapshot.getFamilyId(), snapshot.getListCode(), snapshot.getListName(),
                snapshot.getTemplateId(), snapshot.getTemplateName(), snapshot.getMajor(), snapshot.getMinor(), snapshot.getLabel(),
                snapshot.getTableName(), snapshot.getSourceVersionId(), snapshot.getFingerprint(), snapshot.getRowCount(),
                snapshot.getStoredRows(), snapshot.getPreviousSnapshotId(), snapshot.getPreviousLabel(), snapshot.getInsertedRows(),
                snapshot.getDeletedRows(), snapshot.getChangedRows(), snapshot.getCreatedAt(), snapshot.getCreatedBy(),
                snapshot.getCreatedByName(), snapshot.getCreatedJobId(), snapshot.getReuseCount(), snapshot.getLastUsedAt());
    }
}
