package com.bnpp.export.controller;

import com.bnpp.export.config.MockProperties;
import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.QuarantineResponse;
import com.bnpp.export.dto.SubscriberRequest;
import com.bnpp.export.dto.TriggerExportRequest;
import com.bnpp.export.model.CertifiedVersion;
import com.bnpp.export.model.DeliveryReport;
import com.bnpp.export.model.ExportJob;
import com.bnpp.export.model.QuarantineRecord;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.ExportOrchestrator;
import com.bnpp.export.service.FormatTemplateService;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api")
public class ExportController {

    private static final Set<String> SCOPES = Set.of("ALL", "SANCTIONS", "WORLDCHECK", "PFA");
    private static final Set<String> MODES = Set.of("FULL", "DELTA");

    private final ExportOrchestrator orchestrator;
    private final com.bnpp.export.service.ListCatalogService listCatalog;
    private final com.bnpp.export.service.TemplateExportService templateExports;
    private final SubscriberRepository subscriberRepository;
    private final QuarantineRepository quarantineRepository;
    private final DeliveryRepository deliveryRepository;
    private final ExportJobSubscriberStatusRepository statusRepository;
    private final MockProperties mockProperties;
    private final AuditService auditService;
    private final FormatTemplateService formatTemplateService;

    public ExportController(ExportOrchestrator orchestrator,
                            com.bnpp.export.service.ListCatalogService listCatalog,
                            com.bnpp.export.service.TemplateExportService templateExports,
                            SubscriberRepository subscriberRepository,
                            QuarantineRepository quarantineRepository,
                            DeliveryRepository deliveryRepository,
                            ExportJobSubscriberStatusRepository statusRepository,
                            MockProperties mockProperties,
                            AuditService auditService,
                            FormatTemplateService formatTemplateService) {
        this.orchestrator = orchestrator;
        this.listCatalog = listCatalog;
        this.templateExports = templateExports;
        this.subscriberRepository = subscriberRepository;
        this.quarantineRepository = quarantineRepository;
        this.deliveryRepository = deliveryRepository;
        this.statusRepository = statusRepository;
        this.mockProperties = mockProperties;
        this.auditService = auditService;
        this.formatTemplateService = formatTemplateService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return orchestrator.dashboard();
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        boolean mock = mockProperties.isEnabled();
        return Map.of(
                "mockEnabled", mock,
                "mode", mock ? "h2" : "postgres",
                "label", mock ? "H2" : "PostgreSQL");
    }

    @GetMapping("/subscribers")
    public List<SubscriberProfile> subscribers(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return subscriberRepository.findAllByOrderByIdAsc().stream()
                .filter(subscriber -> query.isEmpty() || matchesSubscriber(subscriber, query))
                .peek(this::decorate)
                .toList();
    }

    @PostMapping("/subscribers")
    public SubscriberProfile createSubscriber(@Valid @RequestBody SubscriberRequest request) {
        String name = request.name().trim();
        if (subscriberRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(CONFLICT, "A subscriber named " + name + " already exists");
        }
        SubscriberProfile profile = new SubscriberProfile();
        apply(profile, request, name);
        profile.setTemplateFamilyId(request.templateId());
        formatTemplateService.checkChoice(profile.getTemplateFamilyId());
        profile.setActive(request.active() == null || request.active());
        gateNewEndpoint(profile, null);
        SubscriberProfile saved = subscriberRepository.save(profile);
        auditService.subscriber(saved.getId(), "SUBSCRIBER_CREATED", summary(saved));
        return decorate(saved);
    }

    private String summary(SubscriberProfile profile) {
        return profile.getName() + ": " + profile.getFormatType() + " " + profile.getScope() + " " + profile.getExportMode()
                + ", endpoint " + (profile.getCftEndpoint() == null ? "none" : profile.getCftEndpoint() + " (" + profile.getEndpointStatus() + ")")
                + ", template " + java.util.Objects.toString(formatTemplateService.templateName(profile.getTemplateFamilyId()), "none")
                + (profile.isActive() ? ", active" : ", inactive");
    }

    @PutMapping("/subscribers/{id}")
    public SubscriberProfile updateSubscriber(@PathVariable Long id, @Valid @RequestBody SubscriberRequest request) {
        SubscriberProfile profile = subscriberRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscriber not found"));
        String name = request.name().trim();
        subscriberRepository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(CONFLICT, "A subscriber named " + name + " already exists");
                });
        String previousEndpoint = profile.getCftEndpoint();
        String before = summary(profile);
        apply(profile, request, name);
        if (request.templateId() != null) {
            profile.setTemplateFamilyId(request.templateId());
        }
        // A new template must be checked; an unchanged choice is left as it is.
        if (request.templateId() != null) {
            formatTemplateService.checkChoice(profile.getTemplateFamilyId());
        }
        if (request.active() != null) {
            profile.setActive(request.active());
        }
        gateNewEndpoint(profile, previousEndpoint);
        SubscriberProfile saved = subscriberRepository.save(profile);
        String after = summary(saved);
        if (!before.equals(after)) {
            auditService.subscriber(saved.getId(), "SUBSCRIBER_UPDATED", "Before: " + before + ". After: " + after);
        }
        return decorate(saved);
    }

    @PostMapping("/subscribers/{id}/approve-endpoint")
    public SubscriberProfile approveEndpoint(@PathVariable Long id) {
        SubscriberProfile profile = subscriberRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscriber not found"));
        if (!"PENDING".equals(profile.getEndpointStatus())) {
            throw new ResponseStatusException(CONFLICT, "This delivery endpoint is not waiting for approval");
        }
        profile.setEndpointStatus("APPROVED");
        profile.setActive(true);
        SubscriberProfile saved = subscriberRepository.save(profile);
        auditService.subscriber(saved.getId(), "ENDPOINT_APPROVED", saved.getName() + ": " + saved.getCftEndpoint());
        return decorate(saved);
    }

    @PostMapping("/subscribers/{id}/sample")
    public ResponseEntity<Resource> sample(@PathVariable Long id) {
        return orchestrator.sample(id);
    }

    @GetMapping("/subscribers/{id}/last-file")
    public ResponseEntity<Resource> downloadLastSubscriberFile(@PathVariable Long id) {
        return orchestrator.downloadLastSubscriberFile(id);
    }

    @DeleteMapping("/subscribers/{id}")
    public ResponseEntity<Void> deleteSubscriber(@PathVariable Long id) {
        SubscriberProfile profile = subscriberRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscriber not found"));
        if (statusRepository.existsBySubscriberId(id)
                || deliveryRepository.existsBySubscriberId(id)
                || quarantineRepository.existsBySubscriberId(id)) {
            throw new ResponseStatusException(CONFLICT, "Deactivate this subscriber. History still refers to it.");
        }
        auditService.subscriber(id, "SUBSCRIBER_DELETED", summary(profile));
        subscriberRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/versions")
    public List<CertifiedVersion> versions() {
        return orchestrator.versions();
    }

    @PostMapping("/exports/preview")
    public ExportPreview preview(@Valid @RequestBody TriggerExportRequest request) {
        checkDates(request);
        return orchestrator.preview(request.versionId(), request.fromDate(), request.toDate(), request.subscriberIds());
    }

    @PostMapping("/exports")
    public ExportJob trigger(@Valid @RequestBody TriggerExportRequest request) {
        checkDates(request);
        ExportPreview preview = orchestrator.preview(request.versionId(), request.fromDate(), request.toDate(), request.subscriberIds());
        if (request.reviewToken() == null || !request.reviewToken().equals(preview.reviewToken())) {
            throw new ResponseStatusException(CONFLICT, "The review is out of date. Review the export again.");
        }
        return orchestrator.triggerExport(
                request.versionId(),
                CurrentUser.displayName(),
                request.fromDate(),
                request.toDate(),
                request.subscriberIds());
    }

    /** Approved templates with their latest data version and subscribers, searched and paged. */
    @GetMapping("/export-templates")
    public com.bnpp.export.service.TemplateExportService.TemplatePage exportTemplates(@RequestParam(required = false) String q,
                                                                                   @RequestParam(defaultValue = "false") boolean withVersions,
                                                                                   @RequestParam(defaultValue = "0") int offset,
                                                                                   @RequestParam(defaultValue = "25") int limit) {
        return templateExports.list(q, withVersions, offset, limit);
    }

    /** Reads the template's current data: the existing version if nothing changed, otherwise the next version. */
    @PostMapping("/export-templates/{familyId}/latest")
    public com.bnpp.export.service.TemplateExportService.LatestResult exportLatest(@PathVariable Long familyId) {
        return templateExports.exportLatest(familyId);
    }

    /** Lists that can be exported (newest certified version of each), searched and paged. */
    @GetMapping("/lists")
    public com.bnpp.export.service.ListCatalogService.ListPage lists(@RequestParam(required = false) String q,
                                                                    @RequestParam(defaultValue = "recent") String sort,
                                                                    @RequestParam(defaultValue = "0") int offset,
                                                                    @RequestParam(defaultValue = "20") int limit) {
        return listCatalog.lists(q, sort, offset, limit);
    }

    /** Active subscribers, searched and paged, optionally of one format or one template. */
    @GetMapping("/subscribers/search")
    public com.bnpp.export.service.ListCatalogService.SubscriberPage searchSubscribers(@RequestParam(required = false) String q,
                                                                                      @RequestParam(required = false) String format,
                                                                                      @RequestParam(required = false) Long templateFamilyId,
                                                                                      @RequestParam(defaultValue = "0") int offset,
                                                                                      @RequestParam(defaultValue = "25") int limit) {
        return listCatalog.subscribers(q, format, templateFamilyId, offset, limit);
    }

    /** Review of sending a stored data version again: what each chosen subscriber would receive. */
    @PostMapping("/exports/resend/preview")
    public ExportOrchestrator.ResendPreview previewResend(@Valid @RequestBody com.bnpp.export.dto.ResendRequest request) {
        return orchestrator.previewResend(request.snapshotId(), request.subscriberIds(), request.formatType(), request.formats());
    }

    /** Sends a stored data version again, from the stored copy. Needs the token of a matching review. */
    @PostMapping("/exports/resend")
    public ExportJob resend(@Valid @RequestBody com.bnpp.export.dto.ResendRequest request) {
        return orchestrator.triggerResend(request.snapshotId(), request.subscriberIds(), request.reviewToken(), request.formatType(),
                request.formats());
    }

    /** The file formats the module can write. A new format appears here, and in the send dialog, once its writer exists. */
    @GetMapping("/formats")
    public List<String> formats() {
        return orchestrator.formatTypes();
    }

    @PostMapping("/jobs/{jobId}/subscribers/{statusId}/accept")
    public JobDetailResponse accept(@PathVariable Long jobId, @PathVariable Long statusId) {
        return orchestrator.acceptOmissions(jobId, statusId);
    }

    @PostMapping("/jobs/{jobId}/subscribers/{statusId}/retry")
    public JobDetailResponse retry(@PathVariable Long jobId, @PathVariable Long statusId) {
        return orchestrator.retry(jobId, statusId);
    }

    @PostMapping("/jobs/{jobId}/subscribers/{statusId}/proof")
    public DeliveryProofResponse proof(@PathVariable Long jobId, @PathVariable Long statusId) {
        return orchestrator.proof(jobId, statusId);
    }

    @GetMapping("/jobs/{jobId}/subscribers/{statusId}/archive")
    public ResponseEntity<Resource> archive(@PathVariable Long jobId, @PathVariable Long statusId) {
        return orchestrator.downloadArchive(jobId, statusId);
    }

    @GetMapping("/jobs")
    public List<DashboardResponse.JobSummary> jobs(@RequestParam(required = false) String q) {
        return orchestrator.listJobs(q);
    }

    @GetMapping("/jobs/{id}")
    public JobDetailResponse job(@PathVariable Long id) {
        return orchestrator.getJob(id);
    }

    @GetMapping("/jobs/{jobId}/subscribers/{statusId}/file")
    public ResponseEntity<Resource> download(@PathVariable Long jobId, @PathVariable Long statusId) {
        return orchestrator.download(jobId, statusId);
    }

    @GetMapping("/versions/{versionId}/report")
    public DeliveryReport report(@PathVariable Long versionId) {
        return orchestrator.latestReport(versionId);
    }

    @GetMapping("/quarantine")
    public List<QuarantineResponse> quarantine(@RequestParam(required = false) String q) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> names = subscriberRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(SubscriberProfile::getId, SubscriberProfile::getName));
        return quarantineRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(record -> query.isEmpty() || matchesQuarantine(record, names.get(record.getSubscriberId()), query))
                .map(record -> new QuarantineResponse(
                        record.getId(),
                        record.getVersionId(),
                        record.getExportJobId(),
                        record.getSubscriberId(),
                        names.getOrDefault(record.getSubscriberId(), "Subscriber " + record.getSubscriberId()),
                        record.getRecordIdentifier(),
                        record.getQuarantineReason(),
                        record.isPatchApplied(),
                        record.getAcceptedBy(),
                        record.getAcceptedAt(),
                        record.getCreatedAt()))
                .toList();
    }

    private void apply(SubscriberProfile profile, SubscriberRequest request, String name) {
        profile.setName(name);
        // The subscriber's usual format: the one it gets unless another is chosen when sending.
        List<String> formats = orchestrator.formatTypes();
        profile.setFormatType(required(request.formatType(), java.util.Set.copyOf(formats), "Format must be one of " + String.join(", ", formats)));
        profile.setScope(required(request.scope(), SCOPES, "Scope must be ALL, SANCTIONS, WORLDCHECK, or PFA"));
        profile.setExportMode(required(request.exportMode(), MODES, "Mode must be FULL or DELTA"));
        profile.setBusinessLine(StringUtils.trimToNull(request.businessLine()));
        profile.setRegion(StringUtils.trimToNull(request.region()));
        profile.setCftEndpoint(StringUtils.trimToNull(request.cftEndpoint()));
    }

    private void gateNewEndpoint(SubscriberProfile profile, String previousEndpoint) {
        String endpoint = profile.getCftEndpoint();
        if (endpoint == null) {
            profile.setEndpointStatus("APPROVED");
            return;
        }
        if (previousEndpoint != null && endpoint.equalsIgnoreCase(previousEndpoint)) {
            if ("PENDING".equals(profile.getEndpointStatus())) {
                profile.setActive(false);
            }
            return;
        }
        boolean known = subscriberRepository.existsByCftEndpointIgnoreCaseAndEndpointStatus(endpoint, "APPROVED");
        if (!known) {
            profile.setEndpointStatus("PENDING");
            profile.setActive(false);
        } else {
            profile.setEndpointStatus("APPROVED");
        }
    }

    private SubscriberProfile decorate(SubscriberProfile subscriber) {
        subscriber.setTemplateName(formatTemplateService.templateName(subscriber.getTemplateFamilyId()));
        deliveryRepository.findFirstBySubscriberIdOrderByCreatedAtDesc(subscriber.getId()).ifPresent(delivery -> {
            subscriber.setLastDeliveryStatus(delivery.getStatus());
            subscriber.setLastDeliveredAt(delivery.getDeliveredAt());
            if (delivery.getFilePath() != null) {
                subscriber.setLastFileName(Path.of(delivery.getFilePath()).getFileName().toString());
            }
        });
        return subscriber;
    }

    private void checkDates(TriggerExportRequest request) {
        if ((request.fromDate() == null) != (request.toDate() == null)) {
            throw new ResponseStatusException(BAD_REQUEST, "Enter both a from date and a to date, or leave both empty");
        }
        if (request.fromDate() != null && request.fromDate().isAfter(request.toDate())) {
            throw new ResponseStatusException(BAD_REQUEST, "From date must be on or before the to date");
        }
    }

    private String required(String value, Set<String> allowed, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private boolean matchesSubscriber(SubscriberProfile subscriber, String query) {
        return contains(subscriber.getName(), query)
                || contains(subscriber.getFormatType(), query)
                || contains(subscriber.getScope(), query)
                || contains(subscriber.getExportMode(), query)
                || contains(subscriber.getBusinessLine(), query)
                || contains(subscriber.getRegion(), query);
    }

    private boolean matchesQuarantine(QuarantineRecord record, String subscriberName, String query) {
        return contains(record.getRecordIdentifier(), query)
                || contains(record.getQuarantineReason(), query)
                || contains(subscriberName, query)
                || String.valueOf(record.getVersionId()).contains(query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
