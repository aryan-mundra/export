package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.JobPageResponse;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportAuditLogRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.JobService;
import com.bnpp.export.service.StorageService;
import com.bnpp.export.util.OffsetPage;
import com.bnpp.export.util.SqlUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Implementation of {@link JobService}. */
@Service
public class JobServiceImpl implements JobService {

    private final ExportSupport support;
    private final ExportJobRepository jobs;
    private final ExportJobSubscriberStatusRepository files;
    private final ExportAuditLogRepository auditLog;
    private final SubscriberRepository subscribers;
    private final DeliveryRepository deliveries;
    private final StorageService storage;

    public JobServiceImpl(ExportSupport support, ExportJobRepository jobs, ExportJobSubscriberStatusRepository files,
                          ExportAuditLogRepository auditLog, SubscriberRepository subscribers, DeliveryRepository deliveries,
                          StorageService storage) {
        this.support = support;
        this.jobs = jobs;
        this.files = files;
        this.auditLog = auditLog;
        this.subscribers = subscribers;
        this.deliveries = deliveries;
        this.storage = storage;
    }

    @Override
    public DashboardResponse dashboard() {
        return new DashboardResponse(subscribers.count(), subscribers.countByActiveTrue(), jobs.countByStatus("COMPLETED"),
                jobs.countByStatus("FAILED"), jobs.countByStatusIn(List.of("PENDING", "IN_PROGRESS")),
                files.countByStatusIn(List.of("FAILED")),
                jobs.findTop10ByOrderByCreatedAtDesc().stream().map(DashboardResponse.JobSummary::from).toList());
    }

    @Override
    public JobPageResponse jobs(String query, int offset, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        OffsetPage page = OffsetPage.of(offset, limit);
        // Searched and paged in the database: only one page of matching sends is read.
        Page<ExportJob> found = needle.isEmpty()
                ? jobs.findAllByOrderByCreatedAtDescIdDesc(page)
                : jobs.search("%" + SqlUtils.likeEscape(needle) + "%", page);
        return new JobPageResponse(found.getTotalElements(), (int) page.offset(),
                found.getContent().stream().map(DashboardResponse.JobSummary::from).toList());
    }

    @Override
    public JobDetailResponse job(Long jobId) {
        ExportJob job = support.requireJob(jobId);
        List<JobDetailResponse.SubscriberFile> subscriberFiles = files.findByExportJobIdOrderByIdAsc(jobId).stream()
                .map(file -> new JobDetailResponse.SubscriberFile(file.getId(), file.getSubscriberId(), file.getSubscriberName(),
                        file.getStatus(), file.getRecords(), file.getFileSizeBytes(), fileName(file.getFilePath()),
                        fileName(file.getArchivePath()), file.getErrorMessage(), file.getRetryCount(), file.getStartedAt(),
                        file.getCompletedAt()))
                .toList();
        List<JobDetailResponse.AuditEntry> audits = auditLog.findByExportJobIdOrderByTimestampAsc(jobId).stream()
                .map(entry -> new JobDetailResponse.AuditEntry(entry.getAction(), entry.getDetails(), entry.getActor(), entry.getTimestamp()))
                .toList();
        return new JobDetailResponse(job.getId(), job.getFamilyId(), job.getListName(), job.getDataVersionId(), job.getDataVersionNumber(),
                job.getFormatTemplateVersion(), job.getTriggeredBy(), job.getStatus(), job.getTotalSubscribers(),
                job.getCompletedSubscribers(), job.getFailedSubscribers(), job.getTotalRecords(), job.getStartedAt(),
                job.getCompletedAt(), job.getErrorMessage(), job.getCreatedAt(), subscriberFiles, audits);
    }

    @Override
    public DeliveryProofResponse proof(Long jobId, Long fileId) {
        ExportJobSubscriberStatus file = support.requireFile(jobId, fileId);
        if (StringUtils.isBlank(file.getArchivePath())) {
            return new DeliveryProofResponse("NOT_SENT", null, null, null, "Nothing has been sent yet.");
        }
        String archiveHash = storage.hashFile(file.getArchivePath());
        String stored = deliveries.findFirstByFilePathOrderByCreatedAtDesc(file.getFilePath()).map(DeliveryRecord::getFileHash).orElse(null);
        String archiveName = fileName(file.getArchivePath());
        return stored != null && stored.equalsIgnoreCase(archiveHash)
                ? new DeliveryProofResponse("MATCH", stored, archiveHash, archiveName, "The archived file matches the hash recorded on the delivery.")
                : new DeliveryProofResponse("MISMATCH", stored, archiveHash, archiveName,
                "The archive does not match the recorded hash. The archived file is the evidence.");
    }

    @Override
    public FileDownload file(Long jobId, Long fileId) {
        ExportJobSubscriberStatus file = support.requireFile(jobId, fileId);
        if (StringUtils.isBlank(file.getFilePath())) {
            throw new ResourceNotFoundException("Export file not found");
        }
        return stored(file.getFilePath());
    }

    @Override
    public FileDownload archive(Long jobId, Long fileId) {
        ExportJobSubscriberStatus file = support.requireFile(jobId, fileId);
        if (StringUtils.isBlank(file.getArchivePath())) {
            throw new ResourceNotFoundException("Nothing has been sent yet");
        }
        return stored(file.getArchivePath());
    }

    @Override
    public FileDownload lastFile(Long subscriberId) {
        DeliveryRecord delivery = deliveries.findFirstBySubscriberIdOrderByCreatedAtDesc(subscriberId)
                .filter(record -> StringUtils.isNotBlank(record.getFilePath()))
                .orElseThrow(() -> new ResourceNotFoundException("Export file not found"));
        return stored(delivery.getFilePath());
    }

    private FileDownload stored(String path) {
        return FileDownload.stored(fileName(path), storage.load(path));
    }

    private static String fileName(String path) {
        return path == null ? null : Path.of(path).getFileName().toString();
    }
}
