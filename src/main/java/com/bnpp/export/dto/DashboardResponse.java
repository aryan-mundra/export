package com.bnpp.export.dto;

import com.bnpp.export.entity.ExportJob;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The dashboard: subscribers, sends by status, and the most recent sends.
 *
 * @param undeliveredFiles subscriber files that failed and can be retried
 */
public record DashboardResponse(long totalSubscribers, long activeSubscribers, long successfulJobs, long failedJobs,
                                long inProgressJobs, long undeliveredFiles, List<JobSummary> recentJobs) {

    /** A send in lists: which data version of which list, to how many subscribers, and how it went. */
    public record JobSummary(Long id, String listName, Long dataVersionId, int dataVersionNumber, String status, String triggeredBy,
                             long totalRecords, int totalSubscribers, int completedSubscribers, int failedSubscribers,
                             LocalDateTime createdAt) {

        public static JobSummary from(ExportJob job) {
            return new JobSummary(job.getId(), job.getListName(), job.getDataVersionId(), job.getDataVersionNumber(), job.getStatus(),
                    job.getTriggeredBy(), job.getTotalRecords(), job.getTotalSubscribers(), job.getCompletedSubscribers(),
                    job.getFailedSubscribers(), job.getCreatedAt());
        }
    }
}
