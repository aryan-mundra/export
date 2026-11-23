package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

public record JobDetailResponse(
        Long id,
        Long versionId,
        String listCode,
        String listName,
        java.time.LocalDate fromDate,
        java.time.LocalDate toDate,
        String triggeredBy,
        String status,
        int totalSubscribers,
        int completedSubscribers,
        int failedSubscribers,
        int heldSubscribers,
        int skippedSubscribers,
        long totalRecordsProcessed,
        long totalRecordsQuarantined,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage,
        LocalDateTime createdAt,
        List<SubscriberStatusResponse> subscribers,
        List<AuditEntry> audits,
        String mode,
        String snapshotTemplate,
        String snapshotLabel
) {
    public record SubscriberStatusResponse(
            Long id,
            Long subscriberId,
            String subscriberName,
            String formatType,
            String status,
            int recordsProcessed,
            int recordsQuarantined,
            long fileSizeBytes,
            String filePath,
            String fileName,
            String errorMessage,
            int retryCount,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String holdReason,
            String acceptedBy,
            Integer formatTemplateVersion,
            String archiveFileName,
            Long snapshotId,
            String snapshotLabel,
            String snapshotOutcome
    ) {
    }

    public record AuditEntry(
            String action,
            String details,
            String actor,
            LocalDateTime timestamp
    ) {
    }
}
