package com.bnpp.export.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DashboardResponse(
        long totalSubscribers,
        long activeSubscribers,
        long successfulJobs,
        long failedJobs,
        long inProgressJobs,
        long undeliveredSubscribers,
        long openQuarantine,
        List<JobSummary> recentJobs
) {
    public record JobSummary(
            Long id,
            Long versionId,
            String listName,
            LocalDate fromDate,
            LocalDate toDate,
            String status,
            String triggeredBy,
            long totalRecordsProcessed,
            int totalSubscribers,
            int completedSubscribers,
            int failedSubscribers,
            int heldSubscribers,
            int skippedSubscribers,
            int awaitingApprovals,
            LocalDateTime createdAt
    ) {
    }
}
