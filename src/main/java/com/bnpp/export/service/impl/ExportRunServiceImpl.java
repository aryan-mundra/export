package com.bnpp.export.service.impl;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.DataVersionService;
import com.bnpp.export.service.DeliveryService;
import com.bnpp.export.service.ExportRunService;
import com.bnpp.export.service.JobService;
import com.bnpp.export.service.StorageService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.bnpp.export.service.impl.ExportSupport.reason;
import static com.bnpp.export.service.impl.ExportSupport.toInt;
import static com.bnpp.export.service.impl.ExportSupport.trim;

/**
 * Implementation of {@link ExportRunService}: for each subscriber of a send, the stored data version is written as
 * group XML, delivered (two attempts), archived and recorded with its hash. A subscriber's failure never stops the
 * others; the send ends COMPLETED, or FAILED when any file failed (retryable from History).
 */
@Service
public class ExportRunServiceImpl implements ExportRunService {

    private static final Logger log = LoggerFactory.getLogger(ExportRunServiceImpl.class);
    private static final String FORMAT = "XML";

    private final ExportSupport support;
    private final ExportJobRepository jobs;
    private final ExportJobSubscriberStatusRepository files;
    private final SubscriberRepository subscribers;
    private final DataVersionService dataVersions;
    private final StorageService storage;
    private final DeliveryService delivery;
    private final JobService jobService;
    private final AuditService audit;

    public ExportRunServiceImpl(ExportSupport support, ExportJobRepository jobs, ExportJobSubscriberStatusRepository files,
                                SubscriberRepository subscribers, DataVersionService dataVersions, StorageService storage,
                                DeliveryService delivery, JobService jobService, AuditService audit) {
        this.support = support;
        this.jobs = jobs;
        this.files = files;
        this.subscribers = subscribers;
        this.dataVersions = dataVersions;
        this.storage = storage;
        this.delivery = delivery;
        this.jobService = jobService;
        this.audit = audit;
    }

    @Async
    @Override
    public void run(Long jobId) {
        try {
            ExportJob job = support.requireJob(jobId);
            List<SubscriberProfile> recipients = recipients(job);
            job.setStatus("IN_PROGRESS");
            job.setStartedAt(LocalDateTime.now());
            job.setTotalSubscribers(recipients.size());
            jobs.save(job);
            audit.job(jobId, null, "EXPORT_STARTED", "Sending " + job.getListName() + " data version " + job.getDataVersionNumber());
            if (recipients.isEmpty()) {
                finish(jobId, "COMPLETED", "No subscribers");
                return;
            }
            for (SubscriberProfile subscriber : recipients) {
                sendFile(job, subscriber, null);
            }
            settle(jobId);
        } catch (RuntimeException | Error ex) {
            log.error("Export job {} failed", jobId, ex);
            jobs.findById(jobId).ifPresent(found -> finish(jobId, "FAILED", reason(ex)));
        }
    }

    @Override
    public int recoverInterrupted() {
        String reason = "Interrupted: the application stopped while this export was running. Retry the failed subscribers.";
        int recovered = 0;
        for (ExportJob job : jobs.findByStatusIn(List.of("PENDING", "IN_PROGRESS"))) {
            for (ExportJobSubscriberStatus file : files.findByExportJobIdOrderByIdAsc(job.getId())) {
                if ("PENDING".equals(file.getStatus()) || "IN_PROGRESS".equals(file.getStatus())) {
                    file.setStatus("FAILED");
                    file.setErrorMessage(reason);
                    file.setCompletedAt(LocalDateTime.now());
                    files.save(file);
                    audit.job(job.getId(), file.getSubscriberId(), "SUBSCRIBER_FAILED", reason);
                }
            }
            recount(job.getId());
            finish(job.getId(), "FAILED", reason);
            recovered++;
        }
        storage.deleteUnfinishedFiles();
        return recovered;
    }

    @Override
    public JobDetailResponse retry(Long jobId, Long fileId) {
        ExportJob job = support.requireJob(jobId);
        ExportJobSubscriberStatus file = support.requireFile(jobId, fileId);
        if (!"FAILED".equals(file.getStatus())) {
            throw new ConflictException("Only a failed subscriber can be retried");
        }
        job.setStatus("IN_PROGRESS");
        job.setErrorMessage(null);
        jobs.save(job);
        sendFile(job, support.requireSubscriber(file.getSubscriberId()), file);
        settle(jobId);
        return jobService.job(jobId);
    }

    /** The subscribers of a send, in the order they were chosen (deleted ones are skipped). */
    private List<SubscriberProfile> recipients(ExportJob job) {
        List<SubscriberProfile> recipients = new ArrayList<>();
        for (String id : StringUtils.defaultString(job.getSubscriberIds()).split(",")) {
            if (!id.isBlank()) {
                subscribers.findById(Long.valueOf(id.trim())).ifPresent(recipients::add);
            }
        }
        return recipients;
    }

    /** Builds and delivers one subscriber's file. {@code existing} is the failed file being retried, or null. */
    private void sendFile(ExportJob job, SubscriberProfile subscriber, ExportJobSubscriberStatus existing) {
        ExportJobSubscriberStatus file = existing == null ? new ExportJobSubscriberStatus() : existing;
        file.setExportJobId(job.getId());
        file.setSubscriberId(subscriber.getId());
        file.setSubscriberName(subscriber.getName());
        file.setStatus("IN_PROGRESS");
        file.setStartedAt(LocalDateTime.now());
        file.setCompletedAt(null);
        file.setErrorMessage(null);
        file = files.save(file);
        audit.job(job.getId(), subscriber.getId(), "SUBSCRIBER_STARTED", subscriber.getName());
        try {
            ExportFile built = writeFile(job, subscriber, file);
            DeliveryRecord delivered = deliverWithRetry(subscriber, built, job, file);
            file.setArchivePath(delivered.getArchivePath());
            file.setStatus("COMPLETED");
            file.setCompletedAt(LocalDateTime.now());
            files.save(file);
            audit.job(job.getId(), subscriber.getId(), "DELIVERY_SUCCESS", built.getFileName() + " (" + job.getListName() + " data version "
                    + job.getDataVersionNumber() + ", " + file.getRecords() + " records, tracking " + delivered.getCftTrackingId() + ")");
        } catch (RuntimeException | Error ex) {
            // Errors too (out of memory, a broken deployment): a subscriber must never stay "In progress".
            log.warn("Subscriber {} failed on job {}", subscriber.getName(), job.getId(), ex);
            file.setStatus("FAILED");
            file.setErrorMessage(trim(reason(ex)));
            file.setCompletedAt(LocalDateTime.now());
            files.save(file);
            audit.job(job.getId(), subscriber.getId(), "SUBSCRIBER_FAILED", reason(ex));
        } finally {
            recount(job.getId());
        }
    }

    /** Writes the stored data version as XML into a new file, moved to its final name only once complete. */
    private ExportFile writeFile(ExportJob job, SubscriberProfile subscriber, ExportJobSubscriberStatus file) {
        StorageService.PendingFile pending = storage.reserve(subscriber.getName(), job.getDataVersionId(), "xml");
        boolean committed = false;
        try {
            long count;
            try (Writer out = Files.newBufferedWriter(pending.temp(), StandardCharsets.UTF_8)) {
                count = dataVersions.writeXml(job.getDataVersionId(), out);
            }
            ExportFile built = storage.commit(pending, FORMAT);
            committed = true;
            file.setRecords(toInt(count));
            file.setFilePath(built.getFilePath());
            file.setFileSizeBytes(built.getFileSize());
            files.save(file);
            return built;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the export file: " + ex.getMessage(), ex);
        } finally {
            if (!committed) {
                storage.discard(pending);
            }
        }
    }

    private DeliveryRecord deliverWithRetry(SubscriberProfile subscriber, ExportFile file, ExportJob job, ExportJobSubscriberStatus status) {
        String error = "Delivery failed";
        for (int attempt = 1; attempt <= 2; attempt++) {
            DeliveryRecord delivered = delivery.deliver(subscriber, file, job.getDataVersionId(), status.getRecords());
            if ("SUCCESS".equals(delivered.getStatus())) {
                return delivered;
            }
            status.setRetryCount(attempt);
            error = StringUtils.defaultIfBlank(delivered.getErrorMessage(), error);
        }
        throw new IllegalStateException(error);
    }

    /** Recounts a send's files into its counters (one atomic update). */
    private void recount(Long jobId) {
        List<ExportJobSubscriberStatus> rows = files.findByExportJobIdOrderByIdAsc(jobId);
        jobs.updateCounters(jobId, count(rows, "COMPLETED"), count(rows, "FAILED"),
                rows.stream().filter(row -> "COMPLETED".equals(row.getStatus())).mapToLong(ExportJobSubscriberStatus::getRecords).sum());
    }

    private static int count(List<ExportJobSubscriberStatus> rows, String status) {
        return (int) rows.stream().filter(row -> status.equals(row.getStatus())).count();
    }

    /** Once no file is still running, gives the send its final status. */
    private void settle(Long jobId) {
        recount(jobId);
        ExportJob job = support.requireJob(jobId);
        List<ExportJobSubscriberStatus> rows = files.findByExportJobIdOrderByIdAsc(jobId);
        if (rows.stream().anyMatch(row -> "IN_PROGRESS".equals(row.getStatus())) || rows.size() < job.getTotalSubscribers()) {
            return;
        }
        if (job.getFailedSubscribers() > 0) {
            finish(jobId, "FAILED", job.getFailedSubscribers() + " of " + job.getTotalSubscribers() + " subscribers failed");
        } else {
            finish(jobId, "COMPLETED", null);
        }
    }

    /** Marks a send finished once (an atomic update that skips a send already in that status), then audits it. */
    private void finish(Long jobId, String status, String errorMessage) {
        if (jobs.markFinished(jobId, status, LocalDateTime.now(), trim(errorMessage)) == 0) {
            return;
        }
        ExportJob job = support.requireJob(jobId);
        audit.job(jobId, null, "FAILED".equals(status) ? "EXPORT_FAILED" : "EXPORT_COMPLETED",
                status + ": " + job.getCompletedSubscribers() + " of " + job.getTotalSubscribers() + " files delivered, "
                        + job.getTotalRecords() + " records");
    }
}
