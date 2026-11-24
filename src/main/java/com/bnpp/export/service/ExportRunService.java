package com.bnpp.export.service;

import com.bnpp.export.dto.JobDetailResponse;

/**
 * Running export and resend jobs: streaming each subscriber's file, quality checks and quarantine, delivery with
 * retries, the job's counters and final status, and the actions on held or failed files.
 */
public interface ExportRunService {

    /** Runs a queued job in the background. Call it through this interface (the Spring proxy) so it runs async. */
    void runExport(Long jobId);

    /**
     * Runs at start-up: any job or subscriber still PENDING or IN_PROGRESS was interrupted and is marked FAILED,
     * and unfinished files are removed. Assumes one application instance per database.
     */
    int recoverInterruptedExports();

    /** Sends a held file (or closes it when no file was built) once its omissions are accepted. */
    JobDetailResponse acceptOmissions(Long jobId, Long statusId);

    /** Builds and sends a failed subscriber's file again. */
    JobDetailResponse retry(Long jobId, Long statusId);
}
