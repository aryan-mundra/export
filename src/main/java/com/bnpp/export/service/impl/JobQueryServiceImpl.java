package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.DeliveryReportResponse;
import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.JobPageResponse;
import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.format.RecordBatchProvider;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportAuditLogRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.GoldenSourceService;
import com.bnpp.export.service.JobQueryService;
import com.bnpp.export.service.QualityCheckService;
import com.bnpp.export.service.RowFilterService;
import com.bnpp.export.service.StorageService;
import com.bnpp.export.util.OffsetPage;
import com.bnpp.export.util.SqlUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.bnpp.export.service.impl.ExportSupport.noTemplate;
import static com.bnpp.export.service.impl.ExportSupport.onList;
import static com.bnpp.export.service.impl.ExportSupport.write;

/** Implementation of {@link JobQueryService}. */
@Service
public class JobQueryServiceImpl implements JobQueryService {

    private static final int SAMPLE_RECORDS = 10;

    private final ExportSupport support;
    private final SubscriberRepository subscriberRepository;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobSubscriberStatusRepository statusRepository;
    private final ExportAuditLogRepository auditLogRepository;
    private final CertifiedVersionRepository certifiedVersionRepository;
    private final DeliveryRepository deliveryRepository;
    private final QuarantineRepository quarantineRepository;
    private final RecordBatchProvider recordBatchProvider;
    private final QualityCheckService qualityChecker;
    private final StorageService storageService;
    private final FormatTemplateService formatTemplateService;
    private final GoldenSourceService goldenSourceEnricher;
    private final RowFilterService rowFilters;

    public JobQueryServiceImpl(ExportSupport support,
                               SubscriberRepository subscriberRepository,
                               ExportJobRepository exportJobRepository,
                               ExportJobSubscriberStatusRepository statusRepository,
                               ExportAuditLogRepository auditLogRepository,
                               CertifiedVersionRepository certifiedVersionRepository,
                               DeliveryRepository deliveryRepository,
                               QuarantineRepository quarantineRepository,
                               RecordBatchProvider recordBatchProvider,
                               QualityCheckService qualityChecker,
                               StorageService storageService,
                               FormatTemplateService formatTemplateService,
                               GoldenSourceService goldenSourceEnricher,
                               RowFilterService rowFilters) {
        this.support = support;
        this.subscriberRepository = subscriberRepository;
        this.exportJobRepository = exportJobRepository;
        this.statusRepository = statusRepository;
        this.auditLogRepository = auditLogRepository;
        this.certifiedVersionRepository = certifiedVersionRepository;
        this.deliveryRepository = deliveryRepository;
        this.quarantineRepository = quarantineRepository;
        this.recordBatchProvider = recordBatchProvider;
        this.qualityChecker = qualityChecker;
        this.storageService = storageService;
        this.formatTemplateService = formatTemplateService;
        this.goldenSourceEnricher = goldenSourceEnricher;
        this.rowFilters = rowFilters;
    }

    @Override
    public DashboardResponse dashboard() {
        List<DashboardResponse.JobSummary> recent = exportJobRepository.findTop10ByOrderByCreatedAtDesc().stream()
                .map(JobQueryServiceImpl::summary)
                .toList();
        return new DashboardResponse(
                subscriberRepository.count(),
                subscriberRepository.countByActiveTrue(),
                exportJobRepository.countByStatus("COMPLETED"),
                exportJobRepository.countByStatus("FAILED"),
                exportJobRepository.countByStatusIn(List.of("PENDING", "IN_PROGRESS")),
                statusRepository.countByStatusIn(List.of("FAILED", "HELD")),
                quarantineRepository.countByPatchAppliedFalse(),
                recent);
    }

    @Override
    public JobPageResponse listJobs(String query, int offset, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        OffsetPage page = OffsetPage.of(offset, limit);
        // Searched and paged in the database: only one page of matching jobs is read.
        Page<ExportJob> found = needle.isEmpty()
                ? exportJobRepository.findAllByOrderByCreatedAtDescIdDesc(page)
                : exportJobRepository.search("%" + SqlUtils.likeEscape(needle) + "%", page);
        return new JobPageResponse(found.getTotalElements(), (int) page.offset(),
                found.getContent().stream().map(JobQueryServiceImpl::summary).toList());
    }

    @Override
    public JobDetailResponse getJob(Long jobId) {
        return detail(support.requireJob(jobId));
    }

    @Override
    public DeliveryReportResponse latestReport(Long versionId) {
        ExportJob job = exportJobRepository.findFirstByVersionIdOrderByCreatedAtDesc(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("No export job for version " + versionId));
        DeliveryReportResponse report = new DeliveryReportResponse();
        report.setVersionId(job.getVersionId());
        report.setStatus(job.getStatus());
        report.setTotalRecords((int) Math.min(Integer.MAX_VALUE, job.getTotalRecordsProcessed()));
        report.setTotalSubscribers(job.getTotalSubscribers());
        report.setCompletedSubscribers(job.getCompletedSubscribers());
        report.setFailedSubscribers(job.getFailedSubscribers());
        report.setSubscriberStatuses(statusRepository.findByExportJobIdOrderByIdAsc(job.getId()).stream()
                .map(status -> {
                    DeliveryReportResponse.SubscriberDeliveryStatus row = new DeliveryReportResponse.SubscriberDeliveryStatus();
                    row.setSubscriberId(status.getSubscriberId());
                    row.setSubscriberName(status.getSubscriberName());
                    row.setFormatType(status.getFormatType());
                    row.setStatus(status.getStatus());
                    row.setRecordsProcessed(status.getRecordsProcessed());
                    row.setRecordsQuarantined(status.getRecordsQuarantined());
                    row.setFileSizeBytes(status.getFileSizeBytes());
                    row.setFilePath(status.getFilePath());
                    return row;
                })
                .toList());
        return report;
    }

    @Override
    public DeliveryProofResponse proof(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = support.requireStatus(jobId, statusId);
        if (StringUtils.isBlank(status.getArchivePath())) {
            return new DeliveryProofResponse(
                    "NOT_SENT", null, null, status.getFormatTemplateVersion(), status.getFormatType(), null,
                    "Nothing has been sent yet.");
        }
        String archiveHash = storageService.hashFile(status.getArchivePath());
        String stored = deliveryRepository.findFirstByFilePathOrderByCreatedAtDesc(status.getFilePath())
                .map(DeliveryRecord::getFileHash)
                .orElse(null);
        String fileName = Path.of(status.getArchivePath()).getFileName().toString();
        boolean match = stored != null && stored.equalsIgnoreCase(archiveHash);
        if (match) {
            return new DeliveryProofResponse(
                    "MATCH", stored, archiveHash, status.getFormatTemplateVersion(), status.getFormatType(), fileName,
                    "The archived file matches the hash recorded on the delivery.");
        }
        return new DeliveryProofResponse(
                "MISMATCH", stored, archiveHash, status.getFormatTemplateVersion(), status.getFormatType(), fileName,
                "The archive does not match the recorded hash. The archived file is the evidence.");
    }

    @Override
    public FileDownload download(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = support.requireStatus(jobId, statusId);
        if (StringUtils.isBlank(status.getFilePath())) {
            throw new ResourceNotFoundException("Export file not found");
        }
        return stored(status.getFilePath());
    }

    @Override
    public FileDownload downloadArchive(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = support.requireStatus(jobId, statusId);
        if (StringUtils.isBlank(status.getArchivePath())) {
            throw new ResourceNotFoundException("Nothing has been sent yet");
        }
        return stored(status.getArchivePath());
    }

    @Override
    public FileDownload downloadLastSubscriberFile(Long subscriberId) {
        DeliveryRecord delivery = deliveryRepository.findFirstBySubscriberIdOrderByCreatedAtDesc(subscriberId)
                .filter(record -> StringUtils.isNotBlank(record.getFilePath()))
                .orElseThrow(() -> new ResourceNotFoundException("Export file not found"));
        return stored(delivery.getFilePath());
    }

    private FileDownload stored(String path) {
        Resource resource = storageService.load(path);
        return FileDownload.stored(Path.of(path).getFileName().toString(), resource);
    }

    @Override
    public FileDownload sample(Long subscriberId) {
        SubscriberProfile subscriber = support.requireSubscriber(subscriberId);
        CertifiedVersion version = certifiedVersionRepository.findAllByOrderByListNameAscVersionIdAsc().stream()
                .filter(item -> onList(subscriber, item.getListCode()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No certified version matches this subscriber"));
        String sampleFormat = support.ownFormats(subscriber).get(0);
        ExportFormat format = support.format(sampleFormat);
        if (format == null) {
            throw new BadRequestException("Unsupported format " + sampleFormat);
        }
        List<String> columns = support.decisionColumns();
        FormatTemplate template = formatTemplateService.inUse(subscriber.getTemplateFamilyId())
                .orElseThrow(() -> new BadRequestException(noTemplate(subscriber)));
        List<FormatField> fields = formatTemplateService.fields(template.getId());
        RowFilterService.Condition rowFilter = rowFilters.condition(formatTemplateService.filters(template.getId()), "e");
        StorageService.PendingFile pending = storageService.reserve(
                subscriber.getName() + "_sample", version.getVersionId(), format.fileExtension());
        long written;
        try {
            try (ExportFormat.RecordWriter writer = format.open(pending.temp(), version.getVersionId(), subscriber.getName(), template.getId())) {
                try {
                    // Stops reading as soon as the sample is full, instead of reading the whole version.
                    recordBatchProvider.stream(version.getVersionId(), subscriber.getScope(), subscriber.getExportMode(),
                            null, null, 50, batch -> {
                                List<Map<String, Object>> matching = batch.stream()
                                        .filter(record -> subscriber.receives(record))
                                        .toList();
                                List<Map<String, Object>> accepted = rowFilters.keep(version.getVersionId(), matching, rowFilter).stream()
                                        .filter(record -> qualityChecker.validateRecord(record, columns) == null)
                                        .toList();
                                for (Map<String, Object> record : goldenSourceEnricher.enrich(accepted, fields)) {
                                    if (writer.count() >= SAMPLE_RECORDS) {
                                        throw new StopStreaming();
                                    }
                                    write(writer, record);
                                }
                                if (writer.count() >= SAMPLE_RECORDS) {
                                    throw new StopStreaming();
                                }
                            });
                } catch (StopStreaming ignored) {
                    // the sample is full
                }
                written = writer.count();
                if (written > 0) {
                    writer.finish();
                }
            }
        } catch (IOException ex) {
            storageService.discard(pending);
            throw new UncheckedIOException("Unable to write the sample file", ex);
        }
        if (written == 0) {
            storageService.discard(pending);
            throw new BadRequestException("No records are available for a sample");
        }
        ExportFile file = storageService.commit(pending, format.formatType());
        return stored(file.getFilePath());
    }

    static DashboardResponse.JobSummary summary(ExportJob job) {
        return new DashboardResponse.JobSummary(
                job.getId(),
                job.getVersionId(),
                job.getListName(),
                job.getFromDate(),
                job.getToDate(),
                job.getStatus(),
                job.getTriggeredBy(),
                job.getTotalRecordsProcessed(),
                job.getTotalSubscribers(),
                job.getCompletedSubscribers(),
                job.getFailedSubscribers(),
                job.getHeldSubscribers(),
                job.getSkippedSubscribers(),
                job.getCreatedAt(),
                job.getMode() == null ? "EXPORT" : job.getMode(),
                job.getSnapshotTemplate(),
                job.getSnapshotLabel(),
                job.getSubscriberIds());
    }

    private JobDetailResponse detail(ExportJob job) {
        List<JobDetailResponse.SubscriberStatusResponse> subscribers =
                statusRepository.findByExportJobIdOrderByIdAsc(job.getId()).stream()
                        .map(status -> new JobDetailResponse.SubscriberStatusResponse(
                                status.getId(),
                                status.getSubscriberId(),
                                status.getSubscriberName(),
                                status.getFormatType(),
                                status.getStatus(),
                                status.getRecordsProcessed(),
                                status.getRecordsQuarantined(),
                                status.getFileSizeBytes(),
                                status.getFilePath(),
                                status.getFilePath() == null ? null : Path.of(status.getFilePath()).getFileName().toString(),
                                status.getErrorMessage(),
                                status.getRetryCount(),
                                status.getStartedAt(),
                                status.getCompletedAt(),
                                status.getHoldReason(),
                                status.getAcceptedBy(),
                                status.getFormatTemplateVersion(),
                                status.getArchivePath() == null ? null : Path.of(status.getArchivePath()).getFileName().toString(),
                                status.getSnapshotId(),
                                status.getSnapshotLabel(),
                                status.getSnapshotOutcome()))
                        .toList();
        List<JobDetailResponse.AuditEntry> audits =
                auditLogRepository.findByExportJobIdOrderByTimestampAsc(job.getId()).stream()
                        .map(entry -> new JobDetailResponse.AuditEntry(entry.getAction(), entry.getDetails(), entry.getActor(), entry.getTimestamp()))
                        .toList();
        return new JobDetailResponse(
                job.getId(),
                job.getVersionId(),
                job.getListCode(),
                job.getListName(),
                job.getFromDate(),
                job.getToDate(),
                job.getTriggeredBy(),
                job.getStatus(),
                job.getTotalSubscribers(),
                job.getCompletedSubscribers(),
                job.getFailedSubscribers(),
                job.getHeldSubscribers(),
                job.getSkippedSubscribers(),
                job.getTotalRecordsProcessed(),
                job.getTotalRecordsQuarantined(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                subscribers,
                audits,
                job.getMode() == null ? "EXPORT" : job.getMode(),
                job.getSnapshotTemplate(),
                job.getSnapshotLabel());
    }

    /** Thrown to stop reading once the sample is full; carries no stack trace. */
    private static final class StopStreaming extends RuntimeException {
        StopStreaming() {
            super(null, null, false, false);
        }
    }
}
