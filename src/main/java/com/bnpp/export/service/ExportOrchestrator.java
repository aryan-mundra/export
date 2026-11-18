package com.bnpp.export.service;

import com.bnpp.export.delivery.CftDeliveryService;
import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.format.CsvFormatGenerator;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.format.RecordBatchProvider;
import com.bnpp.export.model.CertifiedVersion;
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
                              @Lazy ExportOrchestrator self) {
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
        CertifiedVersion version = requireVersion(versionId);
        List<String> columns = decisionColumns(null);
        List<ExportPreview.SubscriberPreview> rows = new ArrayList<>();
        for (SubscriberProfile subscriber : subscriberRepository.findByActiveTrueOrderByIdAsc()) {
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
        CertifiedVersion version = requireVersion(versionId);
        List<SubscriberProfile> subscribers = subscriberRepository.findByActiveTrueOrderByIdAsc();
        ExportJob job = new ExportJob();
        job.setVersionId(versionId);
        job.setFromDate(fromDate);
        job.setToDate(toDate);
        job.setListCode(version.getListCode());
        job.setListName(version.getListName());
        FormatTemplate csvTemplate = formatTemplateService.activeTemplate("CSV").orElse(null);
        if (csvTemplate != null) {
            job.setFormatTemplateId(csvTemplate.getId());
            job.setFormatTemplateVersion(csvTemplate.getVersionNumber());
        }
        job.setTriggeredBy(StringUtils.defaultIfBlank(triggeredBy, CurrentUser.displayName()));
        job.setStatus("PENDING");
        job.setTotalSubscribers(subscribers.size());
        job.setCompletedSubscribers(0);
        job.setFailedSubscribers(0);
        job.setHeldSubscribers(0);
        job.setSkippedSubscribers(0);
        job.setAwaitingApprovals(0);
        job.setTotalRecordsProcessed(0);
        job.setTotalRecordsQuarantined(0);
        job = exportJobRepository.save(job);
        audit(job.getId(), null, "EXPORT_QUEUED",
                version.getListName() + " version " + versionId + " queued for " + subscribers.size() + " subscribers");
        self.runExport(job.getId());
        return job;
    }

    @Async
    public void runExport(Long jobId) {
        try {
            ExportJob job = exportJobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Export job " + jobId + " was not found"));
            List<SubscriberProfile> subscribers = subscriberRepository.findByActiveTrueOrderByIdAsc();
            job.setStatus("IN_PROGRESS");
            job.setStartedAt(LocalDateTime.now());
            job.setTotalSubscribers(subscribers.size());
            exportJobRepository.save(job);
            audit(jobId, null, "EXPORT_STARTED", "Processing " + job.getListName() + " version " + job.getVersionId());

            if (subscribers.isEmpty()) {
                finish(job.getId(), "COMPLETED", "No active subscribers");
                return;
            }
            for (SubscriberProfile subscriber : subscribers) {
                processSubscriber(job, subscriber, null);
            }
            settle(job.getId());
        } catch (RuntimeException ex) {
            log.error("Export job {} failed", jobId, ex);
            exportJobRepository.findById(jobId).ifPresent(found -> finish(jobId, "FAILED", ex.getMessage()));
        }
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
        if (subscriber.isSecretList() && StringUtils.isNotBlank(status.getFilePath())) {
            status.setStatus("AWAITING_APPROVAL");
            statusRepository.save(status);
            audit(jobId, subscriber.getId(), "OMISSIONS_ACCEPTED",
                    acceptedBy + " accepted the omissions. The send still needs an approver.");
        } else if (StringUtils.isBlank(status.getFilePath())) {
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

    public JobDetailResponse approveSend(Long jobId, Long statusId) {
        if (!CurrentUser.approver()) {
            throw new ResponseStatusException(FORBIDDEN, "Only an approver can release a secret-list send");
        }
        ExportJob job = requireJob(jobId);
        ExportJobSubscriberStatus status = requireStatus(jobId, statusId);
        if (!"AWAITING_APPROVAL".equals(status.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "This send is not waiting for an approver");
        }
        SubscriberProfile subscriber = requireSubscriber(status.getSubscriberId());
        release(job, subscriber, status);
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
        Collected collected = collect(version, subscriber, null, null, null, decisionColumns(null));
        List<Map<String, Object>> rows = collected.valid.stream().limit(10).toList();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No records are available for a sample");
        }
        ExportFormat format = formats.get(subscriber.getFormatType().toUpperCase(Locale.ROOT));
        if (format == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported format " + subscriber.getFormatType());
        }
        byte[] content = format.generate(version.getVersionId(), subscriber.getName(), rows);
        ExportFile file = storageService.store(
                subscriber.getName() + "_sample", version.getVersionId(), format.formatType(), format.fileExtension(), content);
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
                statusRepository.countByStatusIn(List.of("FAILED", "HELD", "AWAITING_APPROVAL")),
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
        Collected collected = collect(version, subscriber, fromDate, toDate, null, columns);
        String outcome;
        if (collected.rejected.size() > 0 && subscriber.isSecretList()) {
            outcome = "Held for omissions, then an approver must release the send";
        } else if (collected.rejected.size() > 0) {
            outcome = "Held until omissions are accepted";
        } else if (subscriber.isSecretList()) {
            outcome = "Approver must release this send";
        } else if (collected.valid.isEmpty() && datesApplied) {
            outcome = "Empty delta window. Nothing will be sent";
        } else if ("DELTA".equalsIgnoreCase(subscriber.getExportMode())) {
            outcome = datesApplied ? "Delta file for the date window" : "Delta file. Every change is included";
        } else {
            outcome = "Full file. The date window does not apply";
        }
        return previewRow(subscriber, true, datesApplied, collected.valid.size(), collected.rejected.size(), outcome);
    }

    private ExportPreview.SubscriberPreview previewRow(SubscriberProfile subscriber,
                                                        boolean included,
                                                        boolean datesApplied,
                                                        int records,
                                                        int quarantined,
                                                        String outcome) {
        return new ExportPreview.SubscriberPreview(
                subscriber.getId(),
                subscriber.getName(),
                subscriber.getFormatType(),
                subscriber.getExportMode(),
                subscriber.getScope(),
                subscriber.getBusinessLine(),
                subscriber.getRegion(),
                included,
                datesApplied,
                subscriber.isSecretList(),
                records,
                quarantined,
                outcome);
    }

    private void processSubscriber(ExportJob job, SubscriberProfile subscriber, ExportJobSubscriberStatus existing) {
        ExportJobSubscriberStatus status = existing == null ? new ExportJobSubscriberStatus() : existing;
        status.setExportJobId(job.getId());
        status.setSubscriberId(subscriber.getId());
        status.setSubscriberName(subscriber.getName());
        status.setFormatType(subscriber.getFormatType());
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
                subscriber.getName() + " " + subscriber.getFormatType() + " " + subscriber.getScope() + " " + subscriber.getExportMode());

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

            CertifiedVersion version = requireVersion(job.getVersionId());
            Collected collected = collect(version, subscriber, job.getFromDate(), job.getToDate(), status, decisionColumns(job.getFormatTemplateId()));
            List<QuarantineRecord> quarantined = new ArrayList<>();
            for (Rejected rejected : collected.rejected) {
                quarantined.add(quarantine(job, status, subscriber, rejected.record(), rejected.reason()));
            }
            qualityChecker.saveQuarantineRecords(quarantined);
            status.setRecordsProcessed(collected.valid.size());
            status.setRecordsQuarantined(quarantined.size());
            rememberTemplate(status, job);

            if (collected.valid.isEmpty()) {
                if (quarantined.isEmpty()) {
                    status.setStatus("COMPLETED");
                    status.setCompletedAt(LocalDateTime.now());
                    if ("DELTA".equalsIgnoreCase(subscriber.getExportMode()) && job.getFromDate() != null) {
                        status.setHoldReason("Empty delta window. Nothing was sent.");
                    }
                } else {
                    hold(job, status, quarantined.size() + " record(s) held. No file was built.");
                }
                statusRepository.save(status);
                return;
            }

            ExportFormat format = formats.get(subscriber.getFormatType().toUpperCase(Locale.ROOT));
            if (format == null) {
                throw new IllegalArgumentException("Unsupported format " + subscriber.getFormatType());
            }
            byte[] content = render(job, subscriber, format, collected.valid);
            ExportFile file = storageService.store(
                    subscriber.getName(), job.getVersionId(), format.formatType(), format.fileExtension(), content);
            status.setFilePath(file.getFilePath());
            status.setFileSizeBytes(file.getFileSize());

            if (!quarantined.isEmpty()) {
                hold(job, status, quarantined.size() + " record(s) held. The file is not sent until the omissions are accepted.");
                statusRepository.save(status);
                return;
            }
            if (subscriber.isSecretList()) {
                status.setStatus("AWAITING_APPROVAL");
                status.setHoldReason("Secret list. An approver must release the send.");
                status.setCompletedAt(LocalDateTime.now());
                statusRepository.save(status);
                audit(job.getId(), subscriber.getId(), "SEND_WAITING", status.getHoldReason());
                return;
            }
            DeliveryRecord delivery = deliverWithRetry(
                    subscriber, file, job, status, collected.valid.size(),
                    status.getFormatTemplateId(), status.getFormatTemplateVersion(), null);
            if (!"SUCCESS".equals(delivery.getStatus())) {
                throw new IllegalStateException(StringUtils.defaultIfBlank(delivery.getErrorMessage(), "Delivery failed"));
            }
            status.setArchivePath(delivery.getArchivePath());
            status.setStatus("COMPLETED");
            status.setCompletedAt(LocalDateTime.now());
            statusRepository.save(status);
            audit(job.getId(), subscriber.getId(), "DELIVERY_SUCCESS",
                    file.getFileName() + " (" + collected.valid.size() + " records, tracking " + delivery.getCftTrackingId() + ")");
        } catch (RuntimeException ex) {
            log.warn("Subscriber {} failed on job {}", subscriber.getName(), job.getId(), ex);
            status.setStatus("FAILED");
            status.setErrorMessage(trim(ex.getMessage()));
            status.setCompletedAt(LocalDateTime.now());
            statusRepository.save(status);
            audit(job.getId(), subscriber.getId(), "SUBSCRIBER_FAILED", ex.getMessage());
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
        audit(job.getId(), subscriber.getId(), "DELIVERY_SUCCESS",
                file.getFileName() + " released by " + CurrentUser.displayName());
    }

    private void rememberTemplate(ExportJobSubscriberStatus status, ExportJob job) {
        if (!"CSV".equalsIgnoreCase(status.getFormatType()) || job.getFormatTemplateId() == null) {
            return;
        }
        status.setFormatTemplateId(job.getFormatTemplateId());
        status.setFormatTemplateVersion(job.getFormatTemplateVersion());
    }

    private byte[] render(ExportJob job, SubscriberProfile subscriber, ExportFormat format, List<Map<String, Object>> rows) {
        if (format instanceof CsvFormatGenerator csv) {
            return csv.generate(job.getVersionId(), subscriber.getName(), rows, job.getFormatTemplateId());
        }
        return format.generate(job.getVersionId(), subscriber.getName(), rows);
    }

    private Collected collect(CertifiedVersion version,
                              SubscriberProfile subscriber,
                              LocalDate fromDate,
                              LocalDate toDate,
                              ExportJobSubscriberStatus progress,
                              List<String> columns) {
        Collected collected = new Collected();
        recordBatchProvider.stream(
                version.getVersionId(),
                version.getListCode(),
                subscriber.getExportMode(),
                fromDate,
                toDate,
                200,
                batch -> {
                    for (Map<String, Object> record : batch) {
                        if (!profileMatch(subscriber, record)) {
                            continue;
                        }
                        String reason = qualityChecker.validateRecord(record, columns);
                        if (reason == null) {
                            collected.valid.add(record);
                        } else {
                            collected.rejected.add(new Rejected(record, reason));
                        }
                    }
                    if (progress != null) {
                        progress.setRecordsProcessed(collected.valid.size());
                        progress.setRecordsQuarantined(collected.rejected.size());
                        statusRepository.save(progress);
                    }
                });
        return collected;
    }

    private List<String> decisionColumns(Long templateId) {
        List<FormatField> fields = templateId == null
                ? formatTemplateService.activeFields("CSV")
                : formatTemplateService.fields(templateId);
        return qualityChecker.columnsForRun(fields.stream().map(FormatField::getSourceColumn).toList());
    }

    private String reviewToken(CertifiedVersion version,
                               LocalDate fromDate,
                               LocalDate toDate,
                               List<ExportPreview.SubscriberPreview> rows) {
        FormatTemplate template = formatTemplateService.activeTemplate("CSV").orElse(null);
        StringBuilder raw = new StringBuilder();
        raw.append(version.getVersionId()).append('|')
                .append(fromDate).append('|')
                .append(toDate).append('|')
                .append(template == null ? "none" : template.getId() + ":" + template.getVersionNumber());
        for (ExportPreview.SubscriberPreview row : rows) {
            raw.append('|').append(row.subscriberId())
                    .append(':').append(row.included())
                    .append(':').append(row.records())
                    .append(':').append(row.quarantined())
                    .append(':').append(row.formatType())
                    .append(':').append(row.exportMode())
                    .append(':').append(row.scope());
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
        return subscriber.getScope() == null
                || "ALL".equalsIgnoreCase(subscriber.getScope())
                || listCode.equalsIgnoreCase(subscriber.getScope());
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
                countStatus(rows, "AWAITING_APPROVAL"),
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
        } else if (job.getHeldSubscribers() > 0 || job.getAwaitingApprovals() > 0) {
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
        if (job.getAwaitingApprovals() > 0) {
            parts.add(job.getAwaitingApprovals() + " secret-list send(s) waiting for an approver");
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
        ExportAuditLog entry = new ExportAuditLog();
        entry.setExportJobId(jobId);
        entry.setSubscriberId(subscriberId);
        entry.setAction(action);
        entry.setDetails(trim(details));
        auditLogRepository.save(entry);
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
                job.getAwaitingApprovals(),
                job.getCreatedAt());
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
                                status.getArchivePath() == null ? null : Path.of(status.getArchivePath()).getFileName().toString()))
                        .toList();
        List<JobDetailResponse.AuditEntry> audits =
                auditLogRepository.findByExportJobIdOrderByTimestampAsc(job.getId()).stream()
                        .map(entry -> new JobDetailResponse.AuditEntry(entry.getAction(), entry.getDetails(), entry.getTimestamp()))
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
                job.getAwaitingApprovals(),
                job.getTotalRecordsProcessed(),
                job.getTotalRecordsQuarantined(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                subscribers,
                audits);
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 3900 ? value.substring(0, 3900) : value;
    }

    private static final class Collected {
        private final List<Map<String, Object>> valid = new ArrayList<>();
        private final List<Rejected> rejected = new ArrayList<>();
    }

    private record Rejected(Map<String, Object> record, String reason) {
    }
}
