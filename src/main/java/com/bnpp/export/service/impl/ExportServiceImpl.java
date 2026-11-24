package com.bnpp.export.service.impl;

import com.bnpp.export.dto.CertifiedVersionResponse;
import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.DeliveryReportResponse;
import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.JobPageResponse;
import com.bnpp.export.dto.ResendPreviewResponse;
import com.bnpp.export.dto.ResendRequest;
import com.bnpp.export.dto.TriggerExportRequest;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.service.ExportReviewService;
import com.bnpp.export.service.ExportRunService;
import com.bnpp.export.service.ExportService;
import com.bnpp.export.service.JobQueryService;
import com.bnpp.export.service.ResendService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link ExportService}: one entry point for the controllers, delegating to the service that
 * does each job.
 * <ul>
 *   <li>{@link ExportReviewService}: reviewing and starting an export;</li>
 *   <li>{@link ResendService}: reviewing and starting a send of a stored data version;</li>
 *   <li>{@link ExportRunService}: running jobs, and held or failed files;</li>
 *   <li>{@link JobQueryService}: dashboard, history, job detail, reports and downloads.</li>
 * </ul>
 */
@Service
public class ExportServiceImpl implements ExportService {

    private final ExportReviewService review;
    private final ResendService resend;
    private final ExportRunService run;
    private final JobQueryService queries;

    public ExportServiceImpl(ExportReviewService review, ResendService resend, ExportRunService run, JobQueryService queries) {
        this.review = review;
        this.resend = resend;
        this.run = run;
        this.queries = queries;
    }

    // ------------------------------------------------------------------ export review and start

    @Override
    public ExportPreview previewExport(TriggerExportRequest request) {
        return review.previewExport(request);
    }

    @Override
    public ExportJobResponse startExport(TriggerExportRequest request) {
        return review.startExport(request);
    }

    @Override
    public ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate) {
        return review.preview(versionId, fromDate, toDate, null);
    }

    @Override
    public ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds) {
        return review.preview(versionId, fromDate, toDate, subscriberIds);
    }

    @Override
    public ExportJob triggerExport(Long versionId, String triggeredBy) {
        return review.triggerExport(versionId, triggeredBy, null, null, null);
    }

    @Override
    public ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate) {
        return review.triggerExport(versionId, triggeredBy, fromDate, toDate, null);
    }

    @Override
    public ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds) {
        return review.triggerExport(versionId, triggeredBy, fromDate, toDate, subscriberIds);
    }

    @Override
    public List<CertifiedVersionResponse> versions() {
        return review.versions();
    }

    // ------------------------------------------------------------------ resend

    @Override
    public ResendPreviewResponse previewResend(ResendRequest request) {
        return resend.previewResend(request.snapshotId(), request.subscriberIds(), request.formatType(), request.formats());
    }

    @Override
    public ExportJobResponse startResend(ResendRequest request) {
        return ExportJobResponse.from(resend.triggerResend(request.snapshotId(), request.subscriberIds(), request.reviewToken(),
                request.formatType(), request.formats()));
    }

    @Override
    public ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds) {
        return resend.previewResend(snapshotId, subscriberIds, null, null);
    }

    @Override
    public ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds, String formatType) {
        return resend.previewResend(snapshotId, subscriberIds, formatType, null);
    }

    @Override
    public ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds, String formatType, Map<Long, String> perSubscriber) {
        return resend.previewResend(snapshotId, subscriberIds, formatType, perSubscriber);
    }

    @Override
    public ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken) {
        return resend.triggerResend(snapshotId, subscriberIds, reviewToken, null, null);
    }

    @Override
    public ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType) {
        return resend.triggerResend(snapshotId, subscriberIds, reviewToken, formatType, null);
    }

    @Override
    public ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType,
                                   Map<Long, String> perSubscriber) {
        return resend.triggerResend(snapshotId, subscriberIds, reviewToken, formatType, perSubscriber);
    }

    @Override
    public List<String> formatTypes() {
        return resend.formatTypes();
    }

    // ------------------------------------------------------------------ running jobs

    @Override
    public void runExport(Long jobId) {
        run.runExport(jobId);
    }

    @Override
    public int recoverInterruptedExports() {
        return run.recoverInterruptedExports();
    }

    @Override
    public JobDetailResponse acceptOmissions(Long jobId, Long statusId) {
        return run.acceptOmissions(jobId, statusId);
    }

    @Override
    public JobDetailResponse retry(Long jobId, Long statusId) {
        return run.retry(jobId, statusId);
    }

    // ------------------------------------------------------------------ reading jobs and files

    @Override
    public DashboardResponse dashboard() {
        return queries.dashboard();
    }

    @Override
    public JobPageResponse listJobs(String query, int offset, int limit) {
        return queries.listJobs(query, offset, limit);
    }

    @Override
    public JobDetailResponse getJob(Long jobId) {
        return queries.getJob(jobId);
    }

    @Override
    public DeliveryReportResponse latestReport(Long versionId) {
        return queries.latestReport(versionId);
    }

    @Override
    public DeliveryProofResponse proof(Long jobId, Long statusId) {
        return queries.proof(jobId, statusId);
    }

    @Override
    public FileDownload download(Long jobId, Long statusId) {
        return queries.download(jobId, statusId);
    }

    @Override
    public FileDownload downloadArchive(Long jobId, Long statusId) {
        return queries.downloadArchive(jobId, statusId);
    }

    @Override
    public FileDownload downloadLastSubscriberFile(Long subscriberId) {
        return queries.downloadLastSubscriberFile(subscriberId);
    }

    @Override
    public FileDownload sample(Long subscriberId) {
        return queries.sample(subscriberId);
    }
}
