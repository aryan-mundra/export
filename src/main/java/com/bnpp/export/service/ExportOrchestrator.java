package com.bnpp.export.service;

import com.bnpp.export.delivery.CftDeliveryService;
import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.format.RecordBatchProvider;
import com.bnpp.export.model.CertifiedVersion;
import com.bnpp.export.model.DataSnapshot;
import com.bnpp.export.model.DeliveryRecord;
import com.bnpp.export.model.DeliveryReport;
import com.bnpp.export.model.ExportAuditLog;
import com.bnpp.export.model.ExportFile;
import com.bnpp.export.model.ExportJob;
import com.bnpp.export.model.ExportJobSubscriberStatus;
import com.bnpp.export.model.FormatField;
import com.bnpp.export.model.FormatTemplate;
import com.bnpp.export.model.QuarantineRecord;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportAuditLogRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ExportOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ExportOrchestrator.class);

    private final SubscriberRepository subscriberRepository;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobSubscriberStatusRepository statusRepository;
    private final ExportAuditLogRepository auditLogRepository;
    private final CertifiedVersionRepository certifiedVersionRepository;
    private final DeliveryRepository deliveryRepository;
    private final QuarantineRepository quarantineRepository;
    private final RecordBatchProvider recordBatchProvider;
    private final QualityChecker qualityChecker;
    private final StorageService storageService;
    private final CftDeliveryService cftDeliveryService;
    private final FormatTemplateService formatTemplateService;
    private final Map<String, ExportFormat> formats;
    private final GoldenSourceEnricher goldenSourceEnricher;
    private final ExportManifestService manifestService;
    private final AuditService auditService;
    private final RowFilterService rowFilters;
    private final SnapshotService snapshotService;
    private final ExportOrchestrator self;

    public ExportOrchestrator(SubscriberRepository subscriberRepository,
                              ExportJobRepository exportJobRepository,
                              ExportJobSubscriberStatusRepository statusRepository,
                              ExportAuditLogRepository auditLogRepository,
                              CertifiedVersionRepository certifiedVersionRepository,
                              DeliveryRepository deliveryRepository,
                              QuarantineRepository quarantineRepository,
                              RecordBatchProvider recordBatchProvider,
                              QualityChecker qualityChecker,
                              StorageService storageService,
                              CftDeliveryService cftDeliveryService,
                              FormatTemplateService formatTemplateService,
                              List<ExportFormat> formatList,
                              GoldenSourceEnricher goldenSourceEnricher,
                              ExportManifestService manifestService,
                              AuditService auditService,
                              RowFilterService rowFilters,
                              SnapshotService snapshotService,
                              @Lazy ExportOrchestrator self) {
        this.rowFilters = rowFilters;
        this.snapshotService = snapshotService;
        this.goldenSourceEnricher = goldenSourceEnricher;
        this.manifestService = manifestService;
        this.auditService = auditService;
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
        this.cftDeliveryService = cftDeliveryService;
        this.formatTemplateService = formatTemplateService;
        this.formats = formatList.stream()
                .collect(Collectors.toMap(format -> format.formatType().toUpperCase(Locale.ROOT), format -> format));
        this.self = self;
    }

    public ExportJob triggerExport(Long versionId, String triggeredBy) {
        return triggerExport(versionId, triggeredBy, null, null);
    }

    public ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate) {
        return preview(versionId, fromDate, toDate, null);
    }

    /** The review of an export, for every active subscriber or only the chosen ones. */
    public ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds) {
        CertifiedVersion version = requireVersion(versionId);
        List<String> columns = decisionColumns();
        List<ExportPreview.SubscriberPreview> rows = new ArrayList<>();
        for (SubscriberProfile subscriber : chosenSubscribers(subscriberIds)) {
            rows.add(previewSubscriber(version, subscriber, fromDate, toDate, columns));
        }
        return new ExportPreview(
                version.getVersionId(),
                version.getListCode(),
                version.getListName(),
                version.getContentAsOf(),
                fromDate,
                toDate,
                true,
                reviewToken(version, fromDate, toDate, rows),
                rows);
    }

    public ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate) {
        return triggerExport(versionId, triggeredBy, fromDate, toDate, null);
    }

    /**
     * Every active subscriber when none are given; otherwise the chosen ones, which must exist and be
     * active. Order follows the subscriber ids, so reviews and runs list them the same way.
     */
    private List<SubscriberProfile> chosenSubscribers(List<Long> subscriberIds) {
        if (subscriberIds == null || subscriberIds.isEmpty()) {
            return subscriberRepository.findByActiveTrueOrderByIdAsc();
        }
        List<SubscriberProfile> chosen = new ArrayList<>();
        for (Long id : new java.util.TreeSet<>(subscriberIds)) {
            SubscriberProfile subscriber = requireSubscriber(id);
            if (!subscriber.isActive()) {
                throw new ResponseStatusException(BAD_REQUEST, subscriber.getName() + " is not active");
            }
            chosen.add(subscriber);
        }
        return chosen;
    }

    public ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds) {
        CertifiedVersion version = requireVersion(versionId);
        List<SubscriberProfile> subscribers = chosenSubscribers(subscriberIds);
        ExportJob job = new ExportJob();
        job.setVersionId(versionId);
        job.setFromDate(fromDate);
        job.setToDate(toDate);
        job.setListCode(version.getListCode());
        job.setListName(version.getListName());
        job.setTriggeredBy(StringUtils.defaultIfBlank(triggeredBy, CurrentUser.displayName()));
        job.setMode("EXPORT");
        if (subscriberIds != null && !subscriberIds.isEmpty()) {
            job.setSubscriberIds(String.join(",", subscribers.stream().map(item -> String.valueOf(item.getId())).toList()));
        }
        job.setStatus("PENDING");
        job.setTotalSubscribers(subscribers.size());
        job.setCompletedSubscribers(0);
        job.setFailedSubscribers(0);
        job.setHeldSubscribers(0);
        job.setSkippedSubscribers(0);
        job.setTotalRecordsProcessed(0);
        job.setTotalRecordsQuarantined(0);
        job = exportJobRepository.save(job);
        audit(job.getId(), null, "EXPORT_QUEUED",
                version.getListName() + " version " + versionId + " queued for " + subscribers.size() + " subscribers");
        self.runExport(job.getId());
        return job;
    }

    /** One subscriber in the review of a resend. */
    public record ResendRow(Long subscriberId, String name, String formatType, String scope, String exportMode,
                            long records, long quarantined, String outcome) {
    }

    public record ResendPreview(Long snapshotId, String label, String templateName, String listName, long rowCount,
                                String reviewToken, List<ResendRow> subscribers) {
    }

    /**
     * What resending a stored data version would send to each chosen subscriber: its rows narrowed to
     * the subscriber's scope, business line and region, as an export does. Counted from the stored
     * rows; the source is not read. Any active subscriber can receive it; with no subscribers given,
     * every active subscriber on the version's template.
     *
     * @param formatType one format for every file of this send, or null for each subscriber's own format
     * @param perSubscriber formats per subscriber id (one, or several separated by a comma), overriding {@code formatType}
     */
    public ResendPreview previewResend(Long snapshotId, List<Long> subscriberIds) {
        return previewResend(snapshotId, subscriberIds, null, null);
    }

    public ResendPreview previewResend(Long snapshotId, List<Long> subscriberIds, String formatType) {
        return previewResend(snapshotId, subscriberIds, formatType, null);
    }

    public ResendPreview previewResend(Long snapshotId, List<Long> subscriberIds, String formatType, Map<Long, String> perSubscriber) {
        DataSnapshot snapshot = snapshotService.get(snapshotId);
        String format = sendFormat(formatType);
        Map<Long, List<String>> chosenFormats = new java.util.HashMap<>();
        if (perSubscriber != null) {
            perSubscriber.forEach((id, value) -> {
                List<String> many = sendFormats(value);
                if (id != null && !many.isEmpty()) {
                    chosenFormats.put(id, many);
                }
            });
        }
        if (subscriberIds == null || subscriberIds.isEmpty()) {
            // Every active subscriber on the version's template.
            subscriberIds = subscriberRepository.findByActiveTrueOrderByIdAsc().stream()
                    .filter(item -> snapshot.getFamilyId().equals(item.getTemplateFamilyId()))
                    .map(SubscriberProfile::getId)
                    .toList();
            if (subscriberIds.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST, "No active subscriber uses this list");
            }
        }
        List<FormatField> fields = formatTemplateService.fields(snapshot.getTemplateId());
        List<String> columns = decisionColumns();
        List<ResendRow> rows = new ArrayList<>();
        for (Long id : new java.util.LinkedHashSet<>(subscriberIds)) {
            SubscriberProfile subscriber = requireSubscriber(id);
            if (!subscriber.isActive()) {
                throw new ResponseStatusException(BAD_REQUEST, subscriber.getName() + " is not active");
            }
            long[] counts = new long[2];
            snapshotService.stream(snapshot, new SnapshotService.Selection(subscriber.getScope(), subscriber.getExportMode(), null, null),
                    fields, record -> {
                        if (profileMatch(subscriber, record)) {
                            counts[qualityChecker.validateRecord(record, columns) == null ? 0 : 1]++;
                        }
                    });
            String outcome = counts[1] > 0 ? "Held until omissions are accepted"
                    : counts[0] == 0 ? "Nothing to send for this subscriber" : "Ready to send";
            for (String rowFormat : formatsFor(subscriber, format, chosenFormats)) {
                rows.add(new ResendRow(subscriber.getId(), subscriber.getName(), rowFormat, subscriber.getScope(),
                        subscriber.getExportMode(), counts[0], counts[1], outcome));
            }
        }
        StringBuilder raw = new StringBuilder("resend|" + snapshot.getId() + "|" + snapshot.getFingerprint() + "|" + format);
        rows.forEach(row -> raw.append('|').append(row.subscriberId()).append(':').append(row.formatType()).append(':')
                .append(row.scope()).append(':').append(row.exportMode()).append(':').append(row.records()).append(':').append(row.quarantined()));
        return new ResendPreview(snapshot.getId(), snapshot.getLabel(), snapshot.getTemplateName(), snapshot.getListName(),
                snapshot.getRowCount(), sha256(raw.toString()), rows);
    }

    /** The file formats the module can write, in alphabetical order. */
    public List<String> formatTypes() {
        return formats.keySet().stream().sorted().toList();
    }

    /** One of the module's formats, or null for each subscriber's own format. */
    private String sendFormat(String formatType) {
        if (StringUtils.isBlank(formatType)) {
            return null;
        }
        String format = formatType.trim().toUpperCase(Locale.ROOT);
        if (!formats.containsKey(format)) {
            throw new ResponseStatusException(BAD_REQUEST, "Format must be one of " + String.join(", ", formats.keySet()));
        }
        return format;
    }

    /** One or more formats written as {@code CSV} or {@code CSV,XML}. Blank means none were chosen. */
    private List<String> sendFormats(String value) {
        if (StringUtils.isBlank(value)) {
            return List.of();
        }
        java.util.LinkedHashSet<String> chosen = new java.util.LinkedHashSet<>();
        for (String part : value.split(",")) {
            String format = sendFormat(part);
            if (format != null) {
                chosen.add(format);
            }
        }
        return List.copyOf(chosen);
    }

    /** The files one subscriber receives: the formats chosen for them, else the send's format, else their own. */
    private List<String> formatsFor(SubscriberProfile subscriber, String format, Map<Long, List<String>> chosen) {
        List<String> listed = chosen.get(subscriber.getId());
        if (listed != null && !listed.isEmpty()) {
            return listed;
        }
        if (format != null) {
            return List.of(format);
        }
        return ownFormats(subscriber);
    }

    /** The formats saved for a subscriber, one or more, such as {@code CSV} or {@code CSV,XML}. */
    private List<String> ownFormats(SubscriberProfile subscriber) {
        List<String> listed = sendFormats(subscriber.getFormatType());
        if (listed.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, subscriber.getName() + " has no format");
        }
        return listed;
    }

    /** Sends a stored data version again to the chosen subscribers, after a matching review. */
    public ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken) {
        return triggerResend(snapshotId, subscriberIds, reviewToken, null, null);
    }

    public ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType) {
        return triggerResend(snapshotId, subscriberIds, reviewToken, formatType, null);
    }

    public ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType,
                                   Map<Long, String> perSubscriber) {
        ResendPreview preview = previewResend(snapshotId, subscriberIds, formatType, perSubscriber);
        if (reviewToken == null || !reviewToken.equals(preview.reviewToken())) {
            throw new ResponseStatusException(CONFLICT, "The review is out of date. Review the resend again.");
        }
        DataSnapshot snapshot = snapshotService.get(snapshotId);
        CertifiedVersion version = requireVersion(snapshot.getSourceVersionId());
        ExportJob job = new ExportJob();
        job.setVersionId(version.getVersionId());
        job.setListCode(version.getListCode());
        job.setListName(version.getListName());
        job.setMode("RESEND");
        job.setSnapshotId(snapshot.getId());
        job.setSnapshotLabel(snapshot.getLabel());
        job.setSnapshotTemplate(snapshot.getTemplateName());
        // One entry per file: "id:FORMAT". The same subscriber is listed again for each extra format.
        job.setSubscriberIds(String.join(",", preview.subscribers().stream().map(row -> row.subscriberId() + ":" + row.formatType()).toList()));
        job.setTriggeredBy(CurrentUser.displayName());
        job.setStatus("PENDING");
        job.setTotalSubscribers(preview.subscribers().size());
        job = exportJobRepository.save(job);
        audit(job.getId(), null, "EXPORT_QUEUED", "Resend of " + snapshot.getTemplateName() + " data version " + snapshot.getLabel()
                + " (" + snapshot.getListName() + ") to "
                + String.join(", ", preview.subscribers().stream().map(row -> row.name() + " (" + row.formatType() + ")").toList()));
        self.runExport(job.getId());
        return job;
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
                    .flatMap(subscriber -> ownFormats(subscriber).stream().map(format -> new Delivery(subscriber, format)))
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
                    ownFormats(subscriber).forEach(format -> chosen.add(new Delivery(subscriber, format)));
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
        return job.getFormatOverride() != null ? job.getFormatOverride() : ownFormats(subscriber).get(0);
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Async
    public void runExport(Long jobId) {
        try {
            ExportJob job = exportJobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Export job " + jobId + " was not found"));
            List<Delivery> deliveries = deliveriesFor(job);
            job.setStatus("IN_PROGRESS");
            job.setStartedAt(LocalDateTime.now());
            job.setTotalSubscribers(deliveries.size());
            exportJobRepository.save(job);
            audit(jobId, null, "EXPORT_STARTED", "RESEND".equals(job.getMode())
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

    /** A readable reason, also for errors that carry no message. */
    private static String reason(Throwable ex) {
        String message = ex.getMessage();
        return ex instanceof Error
                ? "Internal error (" + ex.getClass().getSimpleName() + (message == null ? "" : ": " + message) + ")"
                : StringUtils.defaultIfBlank(message, ex.getClass().getSimpleName());
    }

    /**
     * Runs at start-up. An export cannot survive a restart, so any job or subscriber still marked
     * PENDING or IN_PROGRESS was interrupted: mark it FAILED with the reason, so it can be retried
     * instead of showing "In progress" forever. Unfinished files are removed.
     *
     * Assumes one application instance per database. With several instances, give jobs an owner
     * and heartbeat before enabling this on all of them.
     */
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
                    audit(job.getId(), status.getSubscriberId(), "SUBSCRIBER_FAILED", reason);
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

    public JobDetailResponse acceptOmissions(Long jobId, Long statusId) {
        ExportJob job = requireJob(jobId);
        ExportJobSubscriberStatus status = requireStatus(jobId, statusId);
        if (!"HELD".equals(status.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "This file is not waiting for omissions to be accepted");
        }
        SubscriberProfile subscriber = requireSubscriber(status.getSubscriberId());
        String acceptedBy = CurrentUser.displayName();
        qualityChecker.acceptOmissions(status.getId(), acceptedBy);
        status.setAcceptedBy(acceptedBy);
        status.setAcceptedAt(LocalDateTime.now());
        if (StringUtils.isBlank(status.getFilePath())) {
            status.setStatus("COMPLETED");
            status.setCompletedAt(LocalDateTime.now());
            status.setErrorMessage("Omissions accepted. No file was sent.");
            statusRepository.save(status);
            audit(jobId, subscriber.getId(), "OMISSIONS_ACCEPTED", "No file was sent.");
        } else {
            release(job, subscriber, status);
        }
        settle(job.getId());
        return detail(requireJob(jobId));
    }

    public JobDetailResponse retry(Long jobId, Long statusId) {
        ExportJob job = requireJob(jobId);
        ExportJobSubscriberStatus status = requireStatus(jobId, statusId);
        if (!"FAILED".equals(status.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "Only a failed subscriber can be retried");
        }
        SubscriberProfile subscriber = requireSubscriber(status.getSubscriberId());
        job.setStatus("IN_PROGRESS");
        job.setErrorMessage(null);
        exportJobRepository.save(job);
        processSubscriber(job, subscriber, status);
        settle(job.getId());
        return detail(requireJob(jobId));
    }

    public DeliveryProofResponse proof(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = requireStatus(jobId, statusId);
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

    public ResponseEntity<Resource> downloadArchive(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = requireStatus(jobId, statusId);
        if (StringUtils.isBlank(status.getArchivePath())) {
            throw new ResponseStatusException(NOT_FOUND, "Nothing has been sent yet");
        }
        Resource resource = storageService.load(status.getArchivePath());
        String fileName = Path.of(status.getArchivePath()).getFileName().toString();
        MediaType mediaType = fileName.endsWith(".csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_XML;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(mediaType)
                .body(resource);
    }

    public ResponseEntity<Resource> sample(Long subscriberId) {
        SubscriberProfile subscriber = requireSubscriber(subscriberId);
        CertifiedVersion version = certifiedVersionRepository.findAllByOrderByListNameAscVersionIdAsc().stream()
                .filter(item -> onList(subscriber, item.getListCode()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "No certified version matches this subscriber"));
        String sampleFormat = ownFormats(subscriber).get(0);
        ExportFormat format = formats.get(sampleFormat);
        if (format == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported format " + sampleFormat);
        }
        List<String> columns = decisionColumns();
        FormatTemplate template = formatTemplateService.inUse(subscriber.getTemplateFamilyId())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, noTemplate(subscriber)));
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
                                        .filter(record -> profileMatch(subscriber, record))
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
            throw new ResponseStatusException(BAD_REQUEST, "No records are available for a sample");
        }
        ExportFile file = storageService.commit(pending, format.formatType());
        Resource resource = storageService.load(file.getFilePath());
        MediaType mediaType = file.getFileName().endsWith(".csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_XML;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(mediaType)
                .body(resource);
    }

    public DashboardResponse dashboard() {
        List<DashboardResponse.JobSummary> recent = exportJobRepository.findTop10ByOrderByCreatedAtDesc().stream()
                .map(this::summary)
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

    public List<DashboardResponse.JobSummary> listJobs(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return exportJobRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(job -> needle.isEmpty()
                        || String.valueOf(job.getVersionId()).contains(needle)
                        || (job.getTriggeredBy() != null && job.getTriggeredBy().toLowerCase(Locale.ROOT).contains(needle))
                        || (job.getStatus() != null && job.getStatus().toLowerCase(Locale.ROOT).contains(needle))
                        || (job.getListName() != null && job.getListName().toLowerCase(Locale.ROOT).contains(needle)))
                .map(this::summary)
                .toList();
    }

    public List<CertifiedVersion> versions() {
        return certifiedVersionRepository.findAllByOrderByVersionIdAsc().stream()
                .filter(version -> version.isOperatorVisible() && "CERTIFIED".equals(version.getStatus()))
                .toList();
    }

    public JobDetailResponse getJob(Long jobId) {
        return detail(requireJob(jobId));
    }

    public DeliveryReport latestReport(Long versionId) {
        ExportJob job = exportJobRepository.findByVersionIdOrderByCreatedAtDesc(versionId).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No export job for version " + versionId));
        DeliveryReport report = new DeliveryReport();
        report.setVersionId(job.getVersionId());
        report.setStatus(job.getStatus());
        report.setTotalRecords((int) Math.min(Integer.MAX_VALUE, job.getTotalRecordsProcessed()));
        report.setTotalSubscribers(job.getTotalSubscribers());
        report.setCompletedSubscribers(job.getCompletedSubscribers());
        report.setFailedSubscribers(job.getFailedSubscribers());
        report.setSubscriberStatuses(statusRepository.findByExportJobIdOrderByIdAsc(job.getId()).stream()
                .map(status -> {
                    DeliveryReport.SubscriberDeliveryStatus row = new DeliveryReport.SubscriberDeliveryStatus();
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

    public ResponseEntity<Resource> download(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = requireStatus(jobId, statusId);
        if (StringUtils.isBlank(status.getFilePath())) {
            throw new ResponseStatusException(NOT_FOUND, "Export file not found");
        }
        Resource resource = storageService.load(status.getFilePath());
        String fileName = Path.of(status.getFilePath()).getFileName().toString();
        MediaType mediaType = fileName.endsWith(".csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_XML;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(mediaType)
                .body(resource);
    }

    public ResponseEntity<Resource> downloadLastSubscriberFile(Long subscriberId) {
        DeliveryRecord delivery = deliveryRepository.findFirstBySubscriberIdOrderByCreatedAtDesc(subscriberId)
                .filter(record -> StringUtils.isNotBlank(record.getFilePath()))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Export file not found"));
        Resource resource = storageService.load(delivery.getFilePath());
        String fileName = Path.of(delivery.getFilePath()).getFileName().toString();
        MediaType mediaType = fileName.endsWith(".csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_XML;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(mediaType)
                .body(resource);
    }

    private ExportPreview.SubscriberPreview previewSubscriber(CertifiedVersion version,
                                                              SubscriberProfile subscriber,
                                                              LocalDate fromDate,
                                                              LocalDate toDate,
                                                              List<String> columns) {
        boolean included = onList(subscriber, version.getListCode());
        boolean datesApplied = included && "DELTA".equalsIgnoreCase(subscriber.getExportMode()) && fromDate != null;
        if (!included) {
            return previewRow(subscriber, false, false, 0, 0,
                    "Not on the " + version.getListName() + " list");
        }
        FormatTemplate template = formatTemplateService.inUse(subscriber.getTemplateFamilyId()).orElse(null);
        RowFilterService.Condition rowFilter = template == null ? RowFilterService.Condition.NONE
                : rowFilters.condition(formatTemplateService.filters(template.getId()), "e");
        RecordSelector.Counts counts = countFor(version, subscriber, fromDate, toDate, columns, rowFilter);
        String outcome;
        if (template == null) {
            outcome = "Will fail: " + noTemplate(subscriber);
        } else if (counts.rejected() > 0) {
            outcome = "Held until omissions are accepted";
        } else if (counts.valid() == 0 && datesApplied) {
            outcome = "Empty delta window. Nothing will be sent";
        } else if ("DELTA".equalsIgnoreCase(subscriber.getExportMode())) {
            outcome = datesApplied ? "Delta file for the date window" : "Delta file: every changed record";
        } else {
            outcome = "Full file";
        }
        return previewRow(subscriber, true, datesApplied, toInt(counts.valid()), toInt(counts.rejected()), outcome);
    }

    private ExportPreview.SubscriberPreview previewRow(SubscriberProfile subscriber,
                                                        boolean included,
                                                        boolean datesApplied,
                                                        int records,
                                                        int quarantined,
                                                        String outcome) {
        FormatTemplate template = formatTemplateService.inUse(subscriber.getTemplateFamilyId()).orElse(null);
        return new ExportPreview.SubscriberPreview(
                subscriber.getId(),
                subscriber.getName(),
                subscriber.getFormatType(),
                subscriber.getExportMode(),
                subscriber.getScope(),
                subscriber.getBusinessLine(),
                subscriber.getRegion(),
                template == null ? null : template.getName(),
                template == null ? null : template.getVersionNumber(),
                template == null ? List.of() : formatTemplateService.filterSummaries(template.getId()),
                included,
                datesApplied,
                records,
                quarantined,
                outcome);
    }

    private void processSubscriber(ExportJob job, SubscriberProfile subscriber, ExportJobSubscriberStatus existing) {
        processSubscriber(job, subscriber, existing, null);
    }

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
        audit(job.getId(), subscriber.getId(), "SUBSCRIBER_STARTED",
                subscriber.getName() + " " + formatType + " " + subscriber.getScope() + " " + subscriber.getExportMode());

        try {
            if (!onList(subscriber, job.getListCode())) {
                status.setStatus("SKIPPED");
                status.setRecordsProcessed(0);
                status.setRecordsQuarantined(0);
                status.setCompletedAt(LocalDateTime.now());
                status.setHoldReason("Scope " + subscriber.getScope() + " is not on the " + job.getListName() + " list");
                statusRepository.save(status);
                audit(job.getId(), subscriber.getId(), "SUBSCRIBER_SKIPPED", status.getHoldReason());
                return;
            }

            requireVersion(job.getVersionId());
            ExportFormat format = formats.get(formatType.toUpperCase(Locale.ROOT));
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
            audit(job.getId(), subscriber.getId(), "DELIVERY_SUCCESS",
                    file.getFileName() + " (" + streamed.valid + " records, tracking " + delivery.getCftTrackingId() + ")");
        } catch (RuntimeException | Error ex) {
            // Errors too (out of memory, a broken deployment): a subscriber must never stay "In progress".
            log.warn("Subscriber {} failed on job {}", subscriber.getName(), job.getId(), ex);
            status.setStatus("FAILED");
            status.setErrorMessage(trim(reason(ex)));
            status.setCompletedAt(LocalDateTime.now());
            statusRepository.save(status);
            audit(job.getId(), subscriber.getId(), "SUBSCRIBER_FAILED", reason(ex));
        } finally {
            recount(job.getId());
        }
    }

    private void hold(ExportJob job, ExportJobSubscriberStatus status, String reason) {
        status.setStatus("HELD");
        status.setHoldReason(reason);
        status.setCompletedAt(LocalDateTime.now());
        audit(job.getId(), status.getSubscriberId(), "SUBSCRIBER_HELD", reason);
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
            audit(job.getId(), subscriber.getId(), "SUBSCRIBER_FAILED", delivery.getErrorMessage());
            return;
        }
        status.setArchivePath(delivery.getArchivePath());
        status.setStatus("COMPLETED");
        status.setCompletedAt(LocalDateTime.now());
        status.setErrorMessage(null);
        statusRepository.save(status);
        manifestService.markSent(status.getFilePath(), delivery.getArchivePath());
        audit(job.getId(), subscriber.getId(), "DELIVERY_SUCCESS",
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
            audit(job.getId(), subscriber.getId(), "DATA_VERSION_RESENT",
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
        audit(job.getId(), subscriber.getId(), resolution.created() ? "DATA_VERSION_CREATED" : "DATA_VERSION_REUSED",
                template.getName() + " · " + snapshot.getListName() + ": data version " + snapshot.getLabel()
                        + (resolution.created() ? " created (" + snapshot.getRowCount() + " rows)" : " reused, the data has not changed"));
        return snapshot;
    }

    private static String noTemplate(SubscriberProfile subscriber) {
        return subscriber.getTemplateFamilyId() == null
                ? subscriber.getName() + " has no list. Choose one on the Subscribers page."
                : "The list of " + subscriber.getName() + " has no approved version yet.";
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
        List<String> columns = decisionColumns();
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
            java.util.function.Consumer<List<Map<String, Object>>> handle = rows -> {
                List<Map<String, Object>> accepted = new ArrayList<>(rows.size());
                for (Map<String, Object> record : rows) {
                    if (!profileMatch(subscriber, record)) {
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

    private static void write(ExportFormat.RecordWriter writer, Map<String, Object> record) {
        try {
            writer.write(record);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the export file: " + ex.getMessage(), ex);
        }
    }

    /**
     * What a subscriber would receive, for the review step: counted in the database when the reader
     * can do that, otherwise by streaming the records and counting them (still without keeping them).
     */
    private RecordSelector.Counts countFor(CertifiedVersion version,
                                           SubscriberProfile subscriber,
                                           LocalDate fromDate,
                                           LocalDate toDate,
                                           List<String> columns,
                                           RowFilterService.Condition rowFilter) {
        RecordSelector.CountRequest request = new RecordSelector.CountRequest(
                version.getVersionId(), subscriber.getScope(), subscriber.getExportMode(), fromDate, toDate,
                subscriber.getBusinessLine(), subscriber.getRegion(), columns, rowFilter);
        return recordBatchProvider.count(request).orElseGet(() -> {
            long[] counts = new long[2];
            recordBatchProvider.stream(version.getVersionId(), subscriber.getScope(), subscriber.getExportMode(),
                    fromDate, toDate, STREAM_BATCH, batch -> {
                        List<Map<String, Object>> matching = batch.stream()
                                .filter(record -> profileMatch(subscriber, record))
                                .toList();
                        for (Map<String, Object> record : rowFilters.keep(version.getVersionId(), matching, rowFilter)) {
                            counts[qualityChecker.validateRecord(record, columns) == null ? 0 : 1]++;
                        }
                    });
            return new RecordSelector.Counts(counts[0], counts[1]);
        });
    }

    private static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    /** The list entry fields that must not be blank; an entry with a blank one is quarantined. */
    private List<String> decisionColumns() {
        return qualityChecker.columnsForRun(List.of());
    }

    private String reviewToken(CertifiedVersion version,
                               LocalDate fromDate,
                               LocalDate toDate,
                               List<ExportPreview.SubscriberPreview> rows) {
        StringBuilder raw = new StringBuilder();
        raw.append(version.getVersionId()).append('|')
                .append(fromDate).append('|')
                .append(toDate);
        for (ExportPreview.SubscriberPreview row : rows) {
            raw.append('|').append(row.subscriberId())
                    .append(':').append(row.included())
                    .append(':').append(row.records())
                    .append(':').append(row.quarantined())
                    .append(':').append(row.formatType())
                    .append(':').append(row.exportMode())
                    .append(':').append(row.scope())
                    .append(':').append(row.templateName())
                    .append(':').append(row.templateVersion());
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte piece : digest) {
                hex.append(String.format("%02x", piece));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private boolean profileMatch(SubscriberProfile subscriber, Map<String, Object> record) {
        if (StringUtils.isNotBlank(subscriber.getBusinessLine()) && record.containsKey("businessLine")
                && !subscriber.getBusinessLine().equalsIgnoreCase(String.valueOf(record.get("businessLine")))) {
            return false;
        }
        return StringUtils.isBlank(subscriber.getRegion())
                || !record.containsKey("region")
                || subscriber.getRegion().equalsIgnoreCase(String.valueOf(record.get("region")));
    }

    private boolean onList(SubscriberProfile subscriber, String listCode) {
        return true;
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

    private QuarantineRecord quarantine(ExportJob job,
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

    @Transactional
    public void recount(Long jobId) {
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

    private int countStatus(List<ExportJobSubscriberStatus> rows, String status) {
        return (int) rows.stream().filter(row -> status.equals(row.getStatus())).count();
    }

    private void settle(Long jobId) {
        recount(jobId);
        ExportJob job = requireJob(jobId);
        List<ExportJobSubscriberStatus> rows = statusRepository.findByExportJobIdOrderByIdAsc(jobId);
        boolean stillRunning = rows.stream().anyMatch(row -> "IN_PROGRESS".equals(row.getStatus()));
        if (stillRunning || rows.size() < job.getTotalSubscribers()) {
            return;
        }
        if (job.getFailedSubscribers() > 0) {
            finish(jobId, "FAILED", job.getFailedSubscribers() + " of " + job.getTotalSubscribers() + " subscribers failed");
        } else if (job.getHeldSubscribers() > 0) {
            finish(jobId, "HELD", holdMessage(job));
        } else {
            finish(jobId, "COMPLETED", null);
        }
    }

    private String holdMessage(ExportJob job) {
        List<String> parts = new ArrayList<>();
        if (job.getHeldSubscribers() > 0) {
            parts.add(job.getHeldSubscribers() + " file(s) held until omissions are accepted");
        }
        return String.join(". ", parts);
    }

    @Transactional
    public void finish(Long jobId, String status, String errorMessage) {
        int updated = exportJobRepository.markFinished(jobId, status, LocalDateTime.now(), trim(errorMessage));
        if (updated == 0) {
            return;
        }
        ExportJob job = requireJob(jobId);
        String action = switch (status) {
            case "FAILED" -> "EXPORT_FAILED";
            case "HELD" -> "EXPORT_HELD";
            default -> "EXPORT_COMPLETED";
        };
        audit(jobId, null, action,
                status + " records=" + job.getTotalRecordsProcessed() + " quarantined=" + job.getTotalRecordsQuarantined());
    }

    private void audit(Long jobId, Long subscriberId, String action, String details) {
        auditService.job(jobId, subscriberId, action, details);
    }

    private CertifiedVersion requireVersion(Long versionId) {
        return certifiedVersionRepository.findByVersionId(versionId)
                .filter(version -> "CERTIFIED".equals(version.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                        "Choose a certified list version. This catalog is the demo stand-in until the golden source is connected."));
    }

    private ExportJob requireJob(Long jobId) {
        return exportJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Export job not found"));
    }

    private ExportJobSubscriberStatus requireStatus(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = statusRepository.findById(statusId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscriber file not found"));
        if (!jobId.equals(status.getExportJobId())) {
            throw new ResponseStatusException(NOT_FOUND, "Subscriber file not found");
        }
        return status;
    }

    private SubscriberProfile requireSubscriber(Long subscriberId) {
        return subscriberRepository.findById(subscriberId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscriber not found"));
    }

    private DashboardResponse.JobSummary summary(ExportJob job) {
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

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 3900 ? value.substring(0, 3900) : value;
    }

    /** Batch size for reading, quarantining and progress updates while a file streams. */
    private static final int STREAM_BATCH = 500;

    private static final int SAMPLE_RECORDS = 10;

    private static final class Streamed {
        private long valid;
        private long rejected;
        private ExportManifestService.Manifest manifest;
        private ExportFile file;
    }

    private static final class StopStreaming extends RuntimeException {
        StopStreaming() {
            super(null, null, false, false);
        }
    }
}
