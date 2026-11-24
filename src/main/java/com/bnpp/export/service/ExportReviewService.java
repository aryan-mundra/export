package com.bnpp.export.service;

import com.bnpp.export.dto.CertifiedVersionResponse;
import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.TriggerExportRequest;
import com.bnpp.export.entity.ExportJob;

import java.time.LocalDate;
import java.util.List;

/**
 * Exporting a certified list version: the review (what each subscriber would receive, and the review token that
 * proves the user saw it), then queuing the export the user reviewed. The run itself is {@link ExportRunService}.
 */
public interface ExportReviewService {

    /** The review of an export request: its dates are checked, then each subscriber's count. */
    ExportPreview previewExport(TriggerExportRequest request);

    /** Starts the export the user reviewed, as the signed-in user. Refused when the review is out of date. */
    ExportJobResponse startExport(TriggerExportRequest request);

    /** The review of an export, for every active subscriber or only the chosen ones. */
    ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds);

    /** Queues an export job and starts it in the background. */
    ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds);

    /** Certified list versions operators can export, oldest first. */
    List<CertifiedVersionResponse> versions();
}
