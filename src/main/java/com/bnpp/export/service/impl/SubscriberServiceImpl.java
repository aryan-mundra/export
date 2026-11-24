package com.bnpp.export.service.impl;

import com.bnpp.export.dto.SubscriberPageResponse;
import com.bnpp.export.dto.SubscriberRequest;
import com.bnpp.export.dto.SubscriberResponse;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.ExportService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.ListCatalogService;
import com.bnpp.export.service.SubscriberService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.bnpp.export.entity.DeliveryRecord;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Implementation of {@link SubscriberService}. */
@Service
public class SubscriberServiceImpl implements SubscriberService {

    private static final Set<String> SCOPES = Set.of("ALL", "SANCTIONS", "WORLDCHECK", "PFA");
    private static final Set<String> MODES = Set.of("FULL", "DELTA");

    private final SubscriberRepository subscriberRepository;
    private final DeliveryRepository deliveryRepository;
    private final ExportJobSubscriberStatusRepository statusRepository;
    private final QuarantineRepository quarantineRepository;
    private final FormatTemplateService formatTemplateService;
    private final ListCatalogService listCatalog;
    private final ExportService exportService;
    private final AuditService auditService;

    public SubscriberServiceImpl(SubscriberRepository subscriberRepository,
                                 DeliveryRepository deliveryRepository,
                                 ExportJobSubscriberStatusRepository statusRepository,
                                 QuarantineRepository quarantineRepository,
                                 FormatTemplateService formatTemplateService,
                                 ListCatalogService listCatalog,
                                 ExportService exportService,
                                 AuditService auditService) {
        this.subscriberRepository = subscriberRepository;
        this.deliveryRepository = deliveryRepository;
        this.statusRepository = statusRepository;
        this.quarantineRepository = quarantineRepository;
        this.formatTemplateService = formatTemplateService;
        this.listCatalog = listCatalog;
        this.exportService = exportService;
        this.auditService = auditService;
    }

    @Override
    public List<SubscriberResponse> list(String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        // List names and last deliveries for all subscribers at once, not two queries per subscriber.
        Map<Long, String> names = formatTemplateService.templateNames();
        Map<Long, DeliveryRecord> lastDelivery = new HashMap<>();
        for (DeliveryRecord delivery : deliveryRepository.findLatestOfEverySubscriber()) {
            lastDelivery.merge(delivery.getSubscriberId(), delivery, (kept, other) -> other.getId() > kept.getId() ? other : kept);
        }
        return subscriberRepository.findAllByOrderByIdAsc().stream()
                .filter(subscriber -> text.isEmpty() || matches(subscriber, text))
                .map(subscriber -> SubscriberResponse.from(subscriber,
                        subscriber.getTemplateFamilyId() == null ? null : names.get(subscriber.getTemplateFamilyId()),
                        lastDelivery.get(subscriber.getId())))
                .toList();
    }

    @Override
    public SubscriberPageResponse search(String query, String format, Long templateFamilyId, int offset, int limit) {
        return listCatalog.subscribers(query, format, templateFamilyId, offset, limit);
    }

    @Override
    public SubscriberResponse create(SubscriberRequest request) {
        String name = request.name().trim();
        if (subscriberRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A subscriber named " + name + " already exists");
        }
        SubscriberProfile profile = new SubscriberProfile();
        apply(profile, request, name);
        profile.setTemplateFamilyId(request.templateId());
        formatTemplateService.checkChoice(profile.getTemplateFamilyId());
        profile.setActive(request.active() == null || request.active());
        gateNewEndpoint(profile, null);
        SubscriberProfile saved = subscriberRepository.save(profile);
        auditService.subscriber(saved.getId(), "SUBSCRIBER_CREATED", summary(saved));
        return toResponse(saved);
    }

    @Override
    public SubscriberResponse update(Long id, SubscriberRequest request) {
        SubscriberProfile profile = require(id);
        String name = request.name().trim();
        subscriberRepository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ConflictException("A subscriber named " + name + " already exists");
                });
        String previousEndpoint = profile.getCftEndpoint();
        String before = summary(profile);
        apply(profile, request, name);
        // A new list must be checked; an unchanged choice is left as it is.
        if (request.templateId() != null) {
            profile.setTemplateFamilyId(request.templateId());
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
        return toResponse(saved);
    }

    @Override
    public SubscriberResponse approveEndpoint(Long id) {
        SubscriberProfile profile = require(id);
        if (!"PENDING".equals(profile.getEndpointStatus())) {
            throw new ConflictException("This delivery endpoint is not waiting for approval");
        }
        profile.setEndpointStatus("APPROVED");
        profile.setActive(true);
        SubscriberProfile saved = subscriberRepository.save(profile);
        auditService.subscriber(saved.getId(), "ENDPOINT_APPROVED", saved.getName() + ": " + saved.getCftEndpoint());
        return toResponse(saved);
    }

    @Override
    public void delete(Long id) {
        SubscriberProfile profile = require(id);
        if (statusRepository.existsBySubscriberId(id)
                || deliveryRepository.existsBySubscriberId(id)
                || quarantineRepository.existsBySubscriberId(id)) {
            throw new ConflictException("Deactivate this subscriber. History still refers to it.");
        }
        auditService.subscriber(id, "SUBSCRIBER_DELETED", summary(profile));
        subscriberRepository.deleteById(id);
    }

    private SubscriberProfile require(Long id) {
        return subscriberRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Subscriber not found"));
    }

    private SubscriberResponse toResponse(SubscriberProfile subscriber) {
        return SubscriberResponse.from(subscriber, formatTemplateService.templateName(subscriber.getTemplateFamilyId()),
                deliveryRepository.findFirstBySubscriberIdOrderByCreatedAtDesc(subscriber.getId()).orElse(null));
    }

    /** The subscriber in one line, for the audit log. */
    private String summary(SubscriberProfile profile) {
        return profile.getName() + ": " + profile.getFormatType() + " " + profile.getScope() + " " + profile.getExportMode()
                + ", endpoint " + (profile.getCftEndpoint() == null ? "none" : profile.getCftEndpoint() + " (" + profile.getEndpointStatus() + ")")
                + ", list " + Objects.toString(formatTemplateService.templateName(profile.getTemplateFamilyId()), "none")
                + (profile.isActive() ? ", active" : ", inactive");
    }

    private void apply(SubscriberProfile profile, SubscriberRequest request, String name) {
        profile.setName(name);
        // The subscriber's usual formats: the files they get unless the send chooses others.
        profile.setFormatType(formatsOf(request.formatType()));
        profile.setScope(required(request.scope(), SCOPES, "Scope must be ALL, SANCTIONS, WORLDCHECK, or PFA"));
        profile.setExportMode(required(request.exportMode(), MODES, "Mode must be FULL or DELTA"));
        profile.setBusinessLine(StringUtils.trimToNull(request.businessLine()));
        profile.setRegion(StringUtils.trimToNull(request.region()));
        profile.setCftEndpoint(StringUtils.trimToNull(request.cftEndpoint()));
    }

    /**
     * A delivery endpoint no approved subscriber already uses must be approved first: until then the
     * subscriber is inactive. An unchanged endpoint keeps its status.
     */
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
        if (subscriberRepository.existsByCftEndpointIgnoreCaseAndEndpointStatus(endpoint, "APPROVED")) {
            profile.setEndpointStatus("APPROVED");
        } else {
            profile.setEndpointStatus("PENDING");
            profile.setActive(false);
        }
    }

    /** One format, or several separated by a comma, stored in a stable order such as {@code CSV,XML}. */
    private String formatsOf(String value) {
        List<String> known = exportService.formatTypes();
        Set<String> allowed = Set.copyOf(known);
        Set<String> chosen = new LinkedHashSet<>();
        for (String part : value == null ? new String[0] : value.split(",")) {
            String format = part.trim().toUpperCase(Locale.ROOT);
            if (format.isEmpty()) {
                continue;
            }
            if (!allowed.contains(format)) {
                throw new BadRequestException("Format must be one of " + String.join(", ", known));
            }
            chosen.add(format);
        }
        if (chosen.isEmpty()) {
            throw new BadRequestException("Choose at least one format: " + String.join(", ", known));
        }
        return known.stream().filter(chosen::contains).collect(Collectors.joining(","));
    }

    private static String required(String value, Set<String> allowed, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BadRequestException(message);
        }
        return normalized;
    }

    private static boolean matches(SubscriberProfile subscriber, String query) {
        return contains(subscriber.getName(), query)
                || contains(subscriber.getFormatType(), query)
                || contains(subscriber.getScope(), query)
                || contains(subscriber.getExportMode(), query)
                || contains(subscriber.getBusinessLine(), query)
                || contains(subscriber.getRegion(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
