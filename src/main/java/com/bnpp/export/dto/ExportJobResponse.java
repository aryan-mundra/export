package com.bnpp.export.dto;

import com.bnpp.export.entity.ExportJob;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** An export run, or a send of a stored data version (mode RESEND), as returned when it is started. */
public record ExportJobResponse(
        Long id,
        Long versionId,
        LocalDate fromDate,
        LocalDate toDate,
        String listCode,
        String listName,
        Long formatTemplateId,
        Integer formatTemplateVersion,
        String mode,
        Long snapshotId,
        String snapshotLabel,
        String snapshotTemplate,
        String formatOverride,
        String subscriberIds,
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
        LocalDateTime createdAt
) {
    public static ExportJobResponse from(ExportJob job) {
        return new ExportJobResponse(job.getId(), job.getVersionId(), job.getFromDate(), job.getToDate(), job.getListCode(),
                job.getListName(), job.getFormatTemplateId(), job.getFormatTemplateVersion(), job.getMode(), job.getSnapshotId(),
                job.getSnapshotLabel(), job.getSnapshotTemplate(), job.getFormatOverride(), job.getSubscriberIds(),
                job.getTriggeredBy(), job.getStatus(), job.getTotalSubscribers(), job.getCompletedSubscribers(),
                job.getFailedSubscribers(), job.getHeldSubscribers(), job.getSkippedSubscribers(), job.getTotalRecordsProcessed(),
                job.getTotalRecordsQuarantined(), job.getStartedAt(), job.getCompletedAt(), job.getErrorMessage(), job.getCreatedAt());
    }
}
