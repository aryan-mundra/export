package com.bnpp.export.service;

import com.bnpp.export.dto.CertifiedVersionResponse;
import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.DeliveryReportResponse;
import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.ResendPreviewResponse;
import com.bnpp.export.dto.ResendRequest;
import com.bnpp.export.dto.TriggerExportRequest;
import com.bnpp.export.entity.ExportJob;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** ExportService. */
public interface ExportService {

    /** The review of an export request: its dates are checked, then each subscriber's count. */
    ExportPreview previewExport(TriggerExportRequest request);

    /**
     * Starts the export the user reviewed, as the signed-in user. Refused when the review is out of date
     * (the data or the subscribers changed since).
     */
    ExportJobResponse startExport(TriggerExportRequest request);

    /** The review of sending a stored data version to the chosen subscribers, in the chosen formats. */
    ResendPreviewResponse previewResend(ResendRequest request);

    /** Sends a stored data version, from its table, after a matching review. */
    ExportJobResponse startResend(ResendRequest request);

    ExportJob triggerExport(Long versionId, String triggeredBy);

    ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate);

    /** The review of an export, for every active subscriber or only the chosen ones. */
    ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds);

    ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate);

    ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds);

    /**
     * What resending a stored data version would send to each chosen subscriber: its rows narrowed to
     * the subscriber's scope, business line and region, as an export does. Counted from the stored
     * rows; the source is not read. Any active subscriber can receive it; with no subscribers given,
     * every active subscriber on the version's template.
     *
     * @param formatType one format for every file of this send, or null for each subscriber's own format
     * @param perSubscriber formats per subscriber id (one, or several separated by a comma), overriding {@code formatType}
     */
    ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds);

    ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds, String formatType);

    ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds, String formatType, Map<Long, String> perSubscriber);

    /** The file formats the module can write, in alphabetical order. */
    List<String> formatTypes();

    /** Sends a stored data version again to the chosen subscribers, after a matching review. */
    ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken);

    ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType);

    ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType,
                            Map<Long, String> perSubscriber);

    void runExport(Long jobId);

    /**
     * Runs at start-up. An export cannot survive a restart, so any job or subscriber still marked
     * PENDING or IN_PROGRESS was interrupted: mark it FAILED with the reason, so it can be retried
     * instead of showing "In progress" forever. Unfinished files are removed.
     *
     * Assumes one application instance per database. With several instances, give jobs an owner
     * and heartbeat before enabling this on all of them.
     */
    int recoverInterruptedExports();

    JobDetailResponse acceptOmissions(Long jobId, Long statusId);

    JobDetailResponse retry(Long jobId, Long statusId);

    DeliveryProofResponse proof(Long jobId, Long statusId);

    /** The archived copy of a file that was sent. */
    FileDownload downloadArchive(Long jobId, Long statusId);

    /** A small sample file for a subscriber, in its own format, from the newest certified version. */
    FileDownload sample(Long subscriberId);

    DashboardResponse dashboard();

    List<DashboardResponse.JobSummary> listJobs(String query);

    /** Certified list versions operators can export, oldest first. */
    List<CertifiedVersionResponse> versions();

    JobDetailResponse getJob(Long jobId);

    DeliveryReportResponse latestReport(Long versionId);

    /** The file built for one subscriber of a job. */
    FileDownload download(Long jobId, Long statusId);

    /** The last file delivered to a subscriber. */
    FileDownload downloadLastSubscriberFile(Long subscriberId);

    void recount(Long jobId);

    void finish(Long jobId, String status, String errorMessage);
}
