package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

/** A send: its data version, each subscriber's file and delivery, and its audit trail. */
public record JobDetailResponse(Long id, Long familyId, String listName, Long dataVersionId, int dataVersionNumber,
                                Integer formatTemplateVersion, String triggeredBy, String status, int totalSubscribers,
                                int completedSubscribers, int failedSubscribers, long totalRecords, LocalDateTime startedAt,
                                LocalDateTime completedAt, String errorMessage, LocalDateTime createdAt,
                                List<SubscriberFile> subscribers, List<AuditEntry> audits) {

    /** One subscriber's file: its status, size, names of the file and of its archived copy, and any error. */
    public record SubscriberFile(Long id, Long subscriberId, String subscriberName, String status, int records, long fileSizeBytes,
                                 String fileName, String archiveFileName, String errorMessage, int retryCount,
                                 LocalDateTime startedAt, LocalDateTime completedAt) {
    }

    public record AuditEntry(String action, String details, String actor, LocalDateTime timestamp) {
    }
}
