package com.bnpp.export.service;

import com.bnpp.export.dto.JobDetailResponse;

/** Running sends: each subscriber's XML file is written from the stored data version, delivered with retries and archived. */
public interface ExportRunService {

    /** Runs a queued send in the background. Call it through this interface (the Spring proxy) so it runs async. */
    void run(Long jobId);

    /**
     * At start-up: any send or file still PENDING or IN_PROGRESS was interrupted and is marked FAILED (retryable),
     * and unfinished files are removed. Assumes one application instance per database.
     */
    int recoverInterrupted();

    /** Builds and delivers a failed subscriber's file again, from the same data version. */
    JobDetailResponse retry(Long jobId, Long fileId);
}
