package com.bnpp.export.service.impl;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.entity.QuarantineRecord;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.DeliveryService;
import com.bnpp.export.service.ExportManifestService;
import com.bnpp.export.service.ExportRunService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.JobQueryService;
import com.bnpp.export.service.QualityCheckService;
import com.bnpp.export.service.SnapshotService;
import com.bnpp.export.service.StorageService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.bnpp.export.service.impl.ExportSupport.STREAM_BATCH;
import static com.bnpp.export.service.impl.ExportSupport.noTemplate;
import static com.bnpp.export.service.impl.ExportSupport.onList;
import static com.bnpp.export.service.impl.ExportSupport.reason;
import static com.bnpp.export.service.impl.ExportSupport.toInt;
import static com.bnpp.export.service.impl.ExportSupport.trim;
import static com.bnpp.export.service.impl.ExportSupport.write;

/** Implementation of {@link ExportRunService}. */
@Service
public class ExportRunServiceImpl implements ExportRunService {

    private static final Logger log = LoggerFactory.getLogger(ExportRunServiceImpl.class);

    private final ExportSupport support;
    private final JobQueryService jobQueries;
    private final SubscriberRepository subscriberRepository;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobSubscriberStatusRepository statusRepository;
    private final QuarantineRepository quarantineRepository;
    private final QualityCheckService qualityChecker;
    private final StorageService storageService;
    private final DeliveryService cftDeliveryService;
    private final FormatTemplateService formatTemplateService;
    private final ExportManifestService manifestService;
    private final SnapshotService snapshotService;

    public ExportRunServiceImpl(ExportSupport support,
                                JobQueryService jobQueries,
                                SubscriberRepository subscriberRepository,
                                ExportJobRepository exportJobRepository,
                                ExportJobSubscriberStatusRepository statusRepository,
                                QuarantineRepository quarantineRepository,
                                QualityCheckService qualityChecker,
                                StorageService storageService,
                                DeliveryService cftDeliveryService,
                                FormatTemplateService formatTemplateService,
                                ExportManifestService manifestService,
                                SnapshotService snapshotService) {
        this.support = support;
        this.jobQueries = jobQueries;
        this.subscriberRepository = subscriberRepository;
        this.exportJobRepository = exportJobRepository;
        this.statusRepository = statusRepository;
        this.quarantineRepository = quarantineRepository;
        this.qualityChecker = qualityChecker;
        this.storageService = storageService;
        this.cftDeliveryService = cftDeliveryService;
        this.formatTemplateService = formatTemplateService;
        this.manifestService = manifestService;
        this.snapshotService = snapshotService;
    }

    @Async
    @Override
    public void runExport(Long jobId) {
        try {
            ExportJob job = exportJobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Export job " + jobId + " was not found"));
            List<Delivery> deliveries = deliveriesFor(job);
            job.setStatus("IN_PROGRESS");
            job.setStartedAt(LocalDateTime.now());
            job.setTotalSubscribers(deliveries.size());
            exportJobRepository.save(job);
            support.audit(jobId, null, "EXPORT_STARTED", "RESEND".equals(job.getMode())
                    ? "Resending " + job.getSnapshotTemplate() + " data version " + job.getSnapshotLabel()
                    : "Processing " + job.getListName() + " version " + job.getVersionId());

            if (deliveries.isEmpty()) {
                finish(job.getId(), "COMPLETED", "No active subscribers");
                return;
            }
            for (Delivery delivery : deliveries) {
                processSubscriber(job, delivery.subscriber(), null, delivery.format());
            }
            settle(job.getId());
        } catch (RuntimeException | Error ex) {
            log.error("Export job {} failed", jobId, ex);
            exportJobRepository.findById(jobId).ifPresent(found -> finish(jobId, "FAILED", reason(ex)));
        }
    }

    @Override
    public int recoverInterruptedExports() {
        String reason = "Interrupted: the application stopped while this export was running. Retry the failed subscribers.";
        int recovered = 0;
        for (ExportJob job : exportJobRepository.findByStatusIn(List.of("PENDING", "IN_PROGRESS"))) {
            for (ExportJobSubscriberStatus status : statusRepository.findByExportJobIdOrderByIdAsc(job.getId())) {
                if ("PENDING".equals(status.getStatus()) || "IN_PROGRESS".equals(status.getStatus())) {
                    status.setStatus("FAILED");
                    status.setErrorMessage(reason);
                    status.setCompletedAt(LocalDateTime.now());
                    statusRepository.save(status);
                    support.audit(job.getId(), status.getSubscriberId(), "SUBSCRIBER_FAILED", reason);
                }
            }
            recount(job.getId());
            finish(job.getId(), "FAILED", reason);
            recovered++;
        }
        manifestService.failUnfinished();
        storageService.deleteUnfinishedFiles();
        return recovered;
    }

    @Override
    public JobDetailResponse acceptOmissions(Long jobId, Long statusId) {
        ExportJob job = support.requireJob(jobId);
        ExportJobSubscriberStatus status = support.requireStatus(jobId, statusId);
        if (!"HELD".equals(status.getStatus())) {
            throw new ConflictException("This file is not waiting for omissions to be accepted");
        }
        SubscriberProfile subscriber = support.requireSubscriber(status.getSubscriberId());
        String acceptedBy = CurrentUser.displayName();
        qualityChecker.acceptOmissions(status.getId(), acceptedBy);
        status.setAcceptedBy(acceptedBy);
        status.setAcceptedAt(LocalDateTime.now());
        if (StringUtils.isBlank(status.getFilePath())) {
            status.setStatus("COMPLETED");
            status.setCompletedAt(LocalDateTime.now());
            status.setErrorMessage("Omissions accepted. No file was sent.");
            statusRepository.save(status);
            support.audit(jobId, subscriber.getId(), "OMISSIONS_ACCEPTED", "No file was sent.");
        } else {
            release(job, subscriber, status);
        }
        settle(job.getId());
        return jobQueries.getJob(jobId);
    }

    @Override
    public JobDetailResponse retry(Long jobId, Long statusId) {
        ExportJob job = support.requireJob(jobId);
        ExportJobSubscriberStatus status = support.requireStatus(jobId, statusId);
        if (!"FAILED".equals(status.getStatus())) {
            throw new ConflictException("Only a failed subscriber can be retried");
        }
        SubscriberProfile subscriber = support.requireSubscriber(status.getSubscriberId());
        job.setStatus("IN_PROGRESS");
        job.setErrorMessage(null);
        exportJobRepository.save(job);
        processSubscriber(job, subscriber, status, null);
        settle(job.getId());
        return jobQueries.getJob(jobId);
    }

    /** One file of a job: a subscriber and the format that file is written in. */
    private record Delivery(SubscriberProfile subscriber, String format) {
    }

    /**
     * The files a job builds. A resend lists {@code id:FORMAT} once per file, so one subscriber can
     * appear more than once. An ordinary export lists subscriber ids, and each saved format is a file.
     */
    private List<Delivery> deliveriesFor(ExportJob job) {
        if (StringUtils.isBlank(job.getSubscriberIds())) {
            List<SubscriberProfile> active = subscriberRepository.findByActiveTrueOrderByIdAsc();
            if (StringUtils.isNotBlank(job.getFormatOverride())) {
                return active.stream().map(subscriber -> new Delivery(subscriber, job.getFormatOverride())).toList();
            }
            return active.stream()
                    .flatMap(subscriber -> support.ownFormats(subscriber).stream().map(format -> new Delivery(subscriber, format)))
                    .toList();
        }
        List<Delivery> chosen = new ArrayList<>();
        for (String entry : job.getSubscriberIds().split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            subscriberRepository.findById(Long.valueOf(parts[0].trim())).ifPresent(subscriber -> {
                if (parts.length == 2 && !parts[1].isBlank()) {
                    chosen.add(new Delivery(subscriber, parts[1].trim()));
                } else if (StringUtils.isNotBlank(job.getFormatOverride())) {
                    chosen.add(new Delivery(subscriber, job.getFormatOverride()));
                } else {
                    support.ownFormats(subscriber).forEach(format -> chosen.add(new Delivery(subscriber, format)));
                }
            });
        }
        return chosen;
    }

    /**
     * The format of a subscriber's file in this job: the one chosen for it when the job was sent ("id:FORMAT"
     * in the job's subscriber list), else the job's one format for everyone, else the subscriber's own.
     */
    private String formatFor(ExportJob job, SubscriberProfile subscriber) {
        if (StringUtils.isNotBlank(job.getSubscriberIds())) {
            for (String entry : job.getSubscriberIds().split(",")) {
                String[] parts = entry.trim().split(":");
                if (parts.length == 2 && parts[0].equals(String.valueOf(subscriber.getId())) && !parts[1].isBlank()) {
                    return parts[1].trim();
                }
            }
        }
        return job.getFormatOverride() != null ? job.getFormatOverride() : support.ownFormats(subscriber).get(0);
    }

    /**
     * Builds and sends one subscriber's file. {@code existing} is the status row being retried (null on a first
     * run); {@code formatType} is the file's format (null: the retried row's, else the one this job chose).
     */
    private void processSubscriber(ExportJob job, SubscriberProfile subscriber, ExportJobSubscriberStatus existing, String formatType) {
        if (StringUtils.isBlank(formatType)) {
            formatType = existing != null && StringUtils.isNotBlank(existing.getFormatType())
                    ? existing.getFormatType()
                    : formatFor(job, subscriber);
        }
        ExportJobSubscriberStatus status = existing == null ? new ExportJobSubscriberStatus() : existing;
        status.setExportJobId(job.getId());
        status.setSubscriberId(subscriber.getId());
        status.setSubscriberName(subscriber.getName());
        status.setFormatType(formatType);
        status.setStatus("IN_PROGRESS");
        status.setStartedAt(LocalDateTime.now());
        status.setErrorMessage(null);
        status.setCompletedAt(null);
        status.setHoldReason(null);
        if (existing == null) {
            status.setRetryCount(0);
        }
        status = statusRepository.save(status);
        if (existing != null) {
            quarantineRepository.deleteBySubscriberStatusId(status.getId());
            status.setFilePath(null);
            status.setArchivePath(null);
            status.setFileSizeBytes(0);
        }
        support.audit(job.getId(), subscriber.getId(), "SUBSCRIBER_STARTED",
                subscriber.getName() + " " + formatType + " " + subscriber.getScope() + " " + subscriber.getExportMode());

        try {
            if (!onList(subscriber, job.getListCode())) {
                status.setStatus("SKIPPED");
                status.setRecordsProcessed(0);
                status.setRecordsQuarantined(0);
                status.setCompletedAt(LocalDateTime.now());
                status.setHoldReason("Scope " + subscriber.getScope() + " is not on the " + job.getListName() + " list");
                statusRepository.save(status);
                support.audit(job.getId(), subscriber.getId(), "SUBSCRIBER_SKIPPED", status.getHoldReason());
                return;
            }

            support.requireVersion(job.getVersionId());
            ExportFormat format = support.format(formatType);
            if (format == null) {
                throw new IllegalArgumentException("Unsupported format " + formatType);
            }
            DataSnapshot snapshot = snapshotFor(job, status, subscriber);
            Streamed streamed = streamFile(job, subscriber, status, format, snapshot);
            status.setRecordsProcessed(toInt(streamed.valid));
            status.setRecordsQuarantined(toInt(streamed.rejected));

            if (streamed.file == null) {
                if (streamed.rejected == 0) {
                    status.setStatus("COMPLETED");
                    status.setCompletedAt(LocalDateTime.now());
                    if ("DELTA".equalsIgnoreCase(subscriber.getExportMode()) && job.getFromDate() != null) {
                        status.setHoldReason("Empty delta window. Nothing was sent.");
                    }
                } else {
                    hold(job, status, streamed.rejected + " record(s) held. No file was built.");
                }
                statusRepository.save(status);
                return;
            }

            ExportFile file = streamed.file;
            status.setFilePath(file.getFilePath());
            status.setFileSizeBytes(file.getFileSize());

            if (streamed.rejected > 0) {
                hold(job, status, streamed.rejected + " record(s) held. The file is not sent until the omissions are accepted.");
                statusRepository.save(status);
                return;
            }
            DeliveryRecord delivery = deliverWithRetry(
                    subscriber, file, job, status, toInt(streamed.valid),
                    status.getFormatTemplateId(), status.getFormatTemplateVersion(), null);
            if (!"SUCCESS".equals(delivery.getStatus())) {
                throw new IllegalStateException(StringUtils.defaultIfBlank(delivery.getErrorMessage(), "Delivery failed"));
            }
            status.setArchivePath(delivery.getArchivePath());
            status.setStatus("COMPLETED");
            status.setCompletedAt(LocalDateTime.now());
            statusRepository.save(status);
            manifestService.markSent(file.getFilePath(), delivery.getArchivePath());
            support.audit(job.getId(), subscriber.getId(), "DELIVERY_SUCCESS",
                    file.getFileName() + " (" + streamed.valid + " records, tracking " + delivery.getCftTrackingId() + ")");
        } catch (RuntimeException | Error ex) {
            // Errors too (out of memory, a broken deployment): a subscriber must never stay "In progress".
            log.warn("Subscriber {} failed on job {}", subscriber.getName(), job.getId(), ex);
            status.setStatus("FAILED");
            status.setErrorMessage(trim(reason(ex)));
            status.setCompletedAt(LocalDateTime.now());
            statusRepository.save(status);
            support.audit(job.getId(), subscriber.getId(), "SUBSCRIBER_FAILED", reason(ex));
        } finally {
            recount(job.getId());
        }
    }

    private void hold(ExportJob job, ExportJobSubscriberStatus status, String reason) {
        status.setStatus("HELD");
        status.setHoldReason(reason);
        status.setCompletedAt(LocalDateTime.now());
        support.audit(job.getId(), status.getSubscriberId(), "SUBSCRIBER_HELD", reason);
    }

    private void release(ExportJob job, SubscriberProfile subscriber, ExportJobSubscriberStatus status) {
        ExportFile file = new ExportFile();
        file.setFilePath(status.getFilePath());
        file.setFileName(Path.of(status.getFilePath()).getFileName().toString());
        file.setFileSize(status.getFileSizeBytes());
        file.setFileHash(storageService.hashFile(status.getFilePath()));
        file.setFormatType(status.getFormatType());
        DeliveryRecord delivery = deliverWithRetry(
                subscriber, file, job, status, status.getRecordsProcessed(),
                status.getFormatTemplateId(), status.getFormatTemplateVersion(), status.getHoldReason());
        if (!"SUCCESS".equals(delivery.getStatus())) {
            status.setStatus("FAILED");
            status.setErrorMessage(trim(delivery.getErrorMessage()));
            statusRepository.save(status);
            support.audit(job.getId(), subscriber.getId(), "SUBSCRIBER_FAILED", delivery.getErrorMessage());
            return;
        }
        status.setArchivePath(delivery.getArchivePath());
        status.setStatus("COMPLETED");
        status.setCompletedAt(LocalDateTime.now());
        status.setErrorMessage(null);
        statusRepository.save(status);
        manifestService.markSent(status.getFilePath(), delivery.getArchivePath());
        support.audit(job.getId(), subscriber.getId(), "DELIVERY_SUCCESS",
                file.getFileName() + " released by " + CurrentUser.displayName());
    }

    /** Records which template version this subscriber's file is built with: the one in use for its template. */
    private FormatTemplate rememberTemplate(ExportJobSubscriberStatus status, SubscriberProfile subscriber) {
        FormatTemplate template = formatTemplateService.inUse(subscriber.getTemplateFamilyId())
                .orElseThrow(() -> new IllegalStateException(noTemplate(subscriber)));
        status.setFormatTemplateId(template.getId());
        status.setFormatTemplateVersion(template.getVersionNumber());
        return template;
    }

    /**
     * The data version this subscriber's file is built from.
     *
     * A retry uses the version the first attempt used, so it sends the same data. Within one export,
     * subscribers on the same template share one version: the source is read once per template, not
     * once per subscriber. Otherwise the current data is read and matched against stored versions.
     */
    private DataSnapshot snapshotFor(ExportJob job, ExportJobSubscriberStatus status, SubscriberProfile subscriber) {
        if (status.getSnapshotId() == null && job.getSnapshotId() != null) {
            // A resend: the chosen stored version, never the current data.
            DataSnapshot snapshot = snapshotService.get(job.getSnapshotId());
            status.setSnapshotId(snapshot.getId());
            status.setSnapshotLabel(snapshot.getLabel());
            status.setSnapshotOutcome("RESENT");
            status.setFormatTemplateId(snapshot.getTemplateId());
            status.setFormatTemplateVersion(snapshot.getMajor());
            statusRepository.save(status);
            support.audit(job.getId(), subscriber.getId(), "DATA_VERSION_RESENT",
                    snapshot.getTemplateName() + " data version " + snapshot.getLabel() + " sent again from the stored copy");
            return snapshot;
        }
        if (status.getSnapshotId() != null) {
            DataSnapshot snapshot = snapshotService.get(status.getSnapshotId());
            status.setFormatTemplateId(snapshot.getTemplateId());
            status.setFormatTemplateVersion(snapshot.getMajor());
            statusRepository.save(status);
            return snapshot;
        }
        FormatTemplate template = rememberTemplate(status, subscriber);
        for (ExportJobSubscriberStatus sibling : statusRepository.findByExportJobIdOrderByIdAsc(job.getId())) {
            if (!sibling.getId().equals(status.getId()) && sibling.getSnapshotId() != null
                    && template.getId().equals(sibling.getFormatTemplateId())) {
                status.setSnapshotId(sibling.getSnapshotId());
                status.setSnapshotLabel(sibling.getSnapshotLabel());
                status.setSnapshotOutcome(sibling.getSnapshotOutcome());
                statusRepository.save(status);
                return snapshotService.get(sibling.getSnapshotId());
            }
        }
        SnapshotService.Resolution resolution = snapshotService.resolve(job.getVersionId(), template, job.getId());
        DataSnapshot snapshot = resolution.snapshot();
        status.setSnapshotId(snapshot.getId());
        status.setSnapshotLabel(snapshot.getLabel());
        status.setSnapshotOutcome(resolution.created() ? "CREATED" : "REUSED");
        statusRepository.save(status);
        support.audit(job.getId(), subscriber.getId(), resolution.created() ? "DATA_VERSION_CREATED" : "DATA_VERSION_REUSED",
                template.getName() + " · " + snapshot.getListName() + ": data version " + snapshot.getLabel()
                        + (resolution.created() ? " created (" + snapshot.getRowCount() + " rows)" : " reused, the data has not changed"));
        return snapshot;
    }

    /**
     * Streams one subscriber's file from its data version: the stored rows (already filtered by the
     * template and holding their gs_ values) are narrowed to the subscriber's scope, delta window,
     * business line and region, quality-checked, and written to the file and the manifest straight
     * away. The source tables are not read again. Nothing accumulates, so memory use does not grow
     * with the size of the file.
     */
    private Streamed streamFile(ExportJob job,
                                SubscriberProfile subscriber,
                                ExportJobSubscriberStatus status,
                                ExportFormat format,
                                DataSnapshot snapshot) {
        List<String> columns = support.decisionColumns();
        Long templateId = snapshot.getTemplateId();
        List<FormatField> fields = formatTemplateService.fields(templateId);
        StorageService.PendingFile pending = storageService.reserve(subscriber.getName(), job.getVersionId(), format.fileExtension());
        Streamed run = new Streamed();
        List<QuarantineRecord> held = new ArrayList<>();
        ExportFormat.RecordWriter writer = null;
        boolean committed = false;
        try {
            ExportFormat.RecordWriter out = format.open(pending.temp(), job.getVersionId(), subscriber.getName(), templateId);
            writer = out;
            List<Map<String, Object>> batch = new ArrayList<>(STREAM_BATCH);
            Consumer<List<Map<String, Object>>> handle = rows -> {
                List<Map<String, Object>> accepted = new ArrayList<>(rows.size());
                for (Map<String, Object> record : rows) {
                    if (!subscriber.receives(record)) {
                        continue;
                    }
                    String reason = qualityChecker.validateRecord(record, columns);
                    if (reason == null) {
                        accepted.add(record);
                    } else {
                        held.add(quarantine(job, status, subscriber, record, reason));
                        run.rejected++;
                    }
                }
                if (!accepted.isEmpty()) {
                    if (run.manifest == null) {
                        run.manifest = manifestService.begin(job, status, subscriber, format.formatType(),
                                pending.fileName(), pending.target().toString());
                    }
                    for (Map<String, Object> record : accepted) {
                        write(out, record);
                        run.manifest.add(record);
                        run.valid++;
                    }
                }
                if (held.size() >= STREAM_BATCH) {
                    qualityChecker.saveQuarantineRecords(held);
                    held.clear();
                }
                status.setRecordsProcessed(toInt(run.valid));
                status.setRecordsQuarantined(toInt(run.rejected));
                statusRepository.save(status);
            };
            snapshotService.stream(snapshot,
                    new SnapshotService.Selection(subscriber.getScope(), subscriber.getExportMode(), job.getFromDate(), job.getToDate()),
                    fields, record -> {
                        batch.add(record);
                        if (batch.size() >= STREAM_BATCH) {
                            handle.accept(List.copyOf(batch));
                            batch.clear();
                        }
                    });
            if (!batch.isEmpty()) {
                handle.accept(List.copyOf(batch));
            }
            qualityChecker.saveQuarantineRecords(held);
            if (run.valid > 0) {
                out.finish();
            }
            writer = null;
            out.close();
            if (run.valid == 0) {
                return run;
            }
            ExportFile file = storageService.commit(pending, format.formatType());
            run.manifest.finish(file);
            run.file = file;
            committed = true;
            return run;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the export file: " + ex.getMessage(), ex);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // already failing; the unfinished file is discarded below
                }
            }
            if (!committed) {
                storageService.discard(pending);
                if (run.manifest != null) {
                    run.manifest.fail();
                }
            }
        }
    }

    private DeliveryRecord deliverWithRetry(SubscriberProfile subscriber,
                                            ExportFile file,
                                            ExportJob job,
                                            ExportJobSubscriberStatus status,
                                            int recordCount,
                                            Long templateId,
                                            Integer templateVersion,
                                            String quarantineNote) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            DeliveryRecord delivery = cftDeliveryService.deliver(
                    subscriber, file, job.getVersionId(), subscriber.getExportMode(), recordCount,
                    templateId, templateVersion, quarantineNote);
            if ("SUCCESS".equals(delivery.getStatus())) {
                return delivery;
            }
            status.setRetryCount(attempt);
            last = new IllegalStateException(delivery.getErrorMessage());
        }
        throw last == null ? new IllegalStateException("Delivery failed") : last;
    }

    private static QuarantineRecord quarantine(ExportJob job,
                                               ExportJobSubscriberStatus status,
                                               SubscriberProfile subscriber,
                                               Map<String, Object> record,
                                               String reason) {
        QuarantineRecord quarantine = new QuarantineRecord();
        quarantine.setVersionId(job.getVersionId());
        quarantine.setExportJobId(job.getId());
        quarantine.setSubscriberStatusId(status.getId());
        quarantine.setSubscriberId(subscriber.getId());
        quarantine.setRecordIdentifier(record.get("id") == null ? "unknown" : String.valueOf(record.get("id")));
        quarantine.setTargetTable("ver_version_member");
        quarantine.setQuarantineReason(reason);
        quarantine.setPatchApplied(false);
        return quarantine;
    }

    /** Recounts a job's subscribers into its counters (one atomic update). */
    private void recount(Long jobId) {
        List<ExportJobSubscriberStatus> rows = statusRepository.findByExportJobIdOrderByIdAsc(jobId);
        exportJobRepository.updateCounters(
                jobId,
                countStatus(rows, "COMPLETED"),
                countStatus(rows, "FAILED"),
                countStatus(rows, "HELD"),
                countStatus(rows, "SKIPPED"),
                rows.stream().mapToLong(ExportJobSubscriberStatus::getRecordsProcessed).sum(),
                rows.stream().mapToLong(ExportJobSubscriberStatus::getRecordsQuarantined).sum());
    }

    private static int countStatus(List<ExportJobSubscriberStatus> rows, String status) {
        return (int) rows.stream().filter(row -> status.equals(row.getStatus())).count();
    }

    /** Once no subscriber is still running, gives the job its final status. */
    private void settle(Long jobId) {
        recount(jobId);
        ExportJob job = support.requireJob(jobId);
        List<ExportJobSubscriberStatus> rows = statusRepository.findByExportJobIdOrderByIdAsc(jobId);
        boolean stillRunning = rows.stream().anyMatch(row -> "IN_PROGRESS".equals(row.getStatus()));
        if (stillRunning || rows.size() < job.getTotalSubscribers()) {
            return;
        }
        if (job.getFailedSubscribers() > 0) {
            finish(jobId, "FAILED", job.getFailedSubscribers() + " of " + job.getTotalSubscribers() + " subscribers failed");
        } else if (job.getHeldSubscribers() > 0) {
            finish(jobId, "HELD", job.getHeldSubscribers() + " file(s) held until omissions are accepted");
        } else {
            finish(jobId, "COMPLETED", null);
        }
    }

    /** Marks a job finished once (an atomic update that skips a job already in that state), then audits it. */
    private void finish(Long jobId, String status, String errorMessage) {
        int updated = exportJobRepository.markFinished(jobId, status, LocalDateTime.now(), trim(errorMessage));
        if (updated == 0) {
            return;
        }
        ExportJob job = support.requireJob(jobId);
        String action = switch (status) {
            case "FAILED" -> "EXPORT_FAILED";
            case "HELD" -> "EXPORT_HELD";
            default -> "EXPORT_COMPLETED";
        };
        support.audit(jobId, null, action,
                status + " records=" + job.getTotalRecordsProcessed() + " quarantined=" + job.getTotalRecordsQuarantined());
    }

    /** What streaming one file produced. */
    private static final class Streamed {
        private long valid;
        private long rejected;
        private ExportManifestService.Manifest manifest;
        private ExportFile file;
    }
}
