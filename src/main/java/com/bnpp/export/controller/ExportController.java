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
import com.bnpp.export.service.ExportOrchestrator;
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

    private static final Set<String> FORMATS = Set.of("FIRCOSOFT", "GENERIC_XML", "STANDARD_XML", "CSV");
    private static final Set<String> SCOPES = Set.of("ALL", "SANCTIONS", "WORLDCHECK", "PFA");
    private static final Set<String> MODES = Set.of("FULL", "DELTA");

    private final ExportOrchestrator orchestrator;
    private final SubscriberRepository subscriberRepository;
    private final QuarantineRepository quarantineRepository;
    private final DeliveryRepository deliveryRepository;
    private final ExportJobSubscriberStatusRepository statusRepository;
    private final MockProperties mockProperties;

    public ExportController(ExportOrchestrator orchestrator,
                            SubscriberRepository subscriberRepository,
                            QuarantineRepository quarantineRepository,
                            DeliveryRepository deliveryRepository,
                            ExportJobSubscriberStatusRepository statusRepository,
                            MockProperties mockProperties) {
        this.orchestrator = orchestrator;
        this.subscriberRepository = subscriberRepository;
        this.quarantineRepository = quarantineRepository;
        this.deliveryRepository = deliveryRepository;
        this.statusRepository = statusRepository;
        this.mockProperties = mockProperties;
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
                "label", mock ? "H2 Mock" : "PostgreSQL");
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
        profile.setActive(request.active() == null || request.active());
        profile.setSecretList(Boolean.TRUE.equals(request.secretList()));
        gateNewEndpoint(profile, null);
        return decorate(subscriberRepository.save(profile));
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
        apply(profile, request, name);
        if (request.secretList() != null) {
            profile.setSecretList(request.secretList());
        }
        if (request.active() != null) {
            profile.setActive(request.active());
        }
        gateNewEndpoint(profile, previousEndpoint);
        return decorate(subscriberRepository.save(profile));
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
        return decorate(subscriberRepository.save(profile));
    }

    @PostMapping("/subscribers/{id}/sample")
    public ResponseEntity<Resource> sample(@PathVariable Long id) {
        return orchestrator.sample(id);
    }

    @DeleteMapping("/subscribers/{id}")
    public ResponseEntity<Void> deleteSubscriber(@PathVariable Long id) {
        if (!subscriberRepository.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Subscriber not found");
        }
        if (statusRepository.existsBySubscriberId(id)
                || deliveryRepository.existsBySubscriberId(id)
                || quarantineRepository.existsBySubscriberId(id)) {
            throw new ResponseStatusException(CONFLICT, "Deactivate this subscriber. History still refers to it.");
        }
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
        return orchestrator.preview(request.versionId(), request.fromDate(), request.toDate());
    }

    @PostMapping("/exports")
    public ExportJob trigger(@Valid @RequestBody TriggerExportRequest request) {
        checkDates(request);
        ExportPreview preview = orchestrator.preview(request.versionId(), request.fromDate(), request.toDate());
        if (request.reviewToken() == null || !request.reviewToken().equals(preview.reviewToken())) {
            throw new ResponseStatusException(CONFLICT, "The review is out of date. Review the export again.");
        }
        return orchestrator.triggerExport(
                request.versionId(),
                CurrentUser.displayName(),
                request.fromDate(),
                request.toDate());
    }

    @PostMapping("/jobs/{jobId}/subscribers/{statusId}/accept")
    public JobDetailResponse accept(@PathVariable Long jobId, @PathVariable Long statusId) {
        return orchestrator.acceptOmissions(jobId, statusId);
    }

    @PostMapping("/jobs/{jobId}/subscribers/{statusId}/approve-send")
    public JobDetailResponse approveSend(@PathVariable Long jobId, @PathVariable Long statusId) {
        return orchestrator.approveSend(jobId, statusId);
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
        profile.setFormatType(required(request.formatType(), FORMATS, "Format must be FIRCOSOFT, GENERIC_XML, STANDARD_XML, or CSV"));
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
