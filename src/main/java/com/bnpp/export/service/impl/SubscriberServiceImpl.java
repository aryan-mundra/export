package com.bnpp.export.service.impl;

import com.bnpp.export.dto.SubscriberRequest;
import com.bnpp.export.dto.SubscriberResponse;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.SubscriberService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Implementation of {@link SubscriberService}. */
@Service
public class SubscriberServiceImpl implements SubscriberService {

    private final SubscriberRepository subscribers;
    private final DeliveryRepository deliveries;
    private final ExportJobSubscriberStatusRepository files;
    private final FormatTemplateService templates;
    private final AuditService audit;

    public SubscriberServiceImpl(SubscriberRepository subscribers, DeliveryRepository deliveries, ExportJobSubscriberStatusRepository files,
                                 FormatTemplateService templates, AuditService audit) {
        this.subscribers = subscribers;
        this.deliveries = deliveries;
        this.files = files;
        this.templates = templates;
        this.audit = audit;
    }

    @Override
    public List<SubscriberResponse> list(String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        // List names and last deliveries of every subscriber at once, not two queries per subscriber.
        Map<Long, String> names = templates.templateNames();
        Map<Long, DeliveryRecord> lastDelivery = new HashMap<>();
        for (DeliveryRecord delivery : deliveries.findLatestOfEverySubscriber()) {
            lastDelivery.merge(delivery.getSubscriberId(), delivery, (kept, other) -> other.getId() > kept.getId() ? other : kept);
        }
        return subscribers.findAllByOrderByIdAsc().stream()
                .map(subscriber -> SubscriberResponse.from(subscriber, names.get(subscriber.getTemplateFamilyId()), lastDelivery.get(subscriber.getId())))
                .filter(subscriber -> text.isEmpty() || contains(subscriber.name(), text) || contains(subscriber.cftEndpoint(), text)
                        || contains(subscriber.templateName(), text) || contains(subscriber.businessLine(), text) || contains(subscriber.region(), text)
                        || contains(subscriber.scope(), text))
                .toList();
    }

    @Override
    public SubscriberResponse create(SubscriberRequest request) {
        String name = request.name().trim();
        if (subscribers.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A subscriber named " + name + " already exists");
        }
        SubscriberProfile profile = new SubscriberProfile();
        profile.setName(name);
        applySettings(profile, request);
        profile.setCftEndpoint(StringUtils.trimToNull(request.cftEndpoint()));
        profile.setTemplateFamilyId(request.templateId());
        templates.checkChoice(profile.getTemplateFamilyId());
        profile.setActive(request.active() == null || request.active());
        gateNewEndpoint(profile, null);
        SubscriberProfile saved = subscribers.save(profile);
        audit.subscriber(saved.getId(), "SUBSCRIBER_CREATED", summary(saved));
        return toResponse(saved);
    }

    @Override
    public SubscriberResponse update(Long id, SubscriberRequest request) {
        SubscriberProfile profile = require(id);
        String name = request.name().trim();
        subscribers.findByNameIgnoreCase(name).filter(existing -> !existing.getId().equals(id)).ifPresent(existing -> {
            throw new ConflictException("A subscriber named " + name + " already exists");
        });
        String previousEndpoint = profile.getCftEndpoint();
        String before = summary(profile);
        profile.setName(name);
        applySettings(profile, request);
        profile.setCftEndpoint(StringUtils.trimToNull(request.cftEndpoint()));
        if (request.templateId() != null) {
            profile.setTemplateFamilyId(request.templateId());
            templates.checkChoice(profile.getTemplateFamilyId());
        }
        if (request.active() != null) {
            profile.setActive(request.active());
        }
        gateNewEndpoint(profile, previousEndpoint);
        SubscriberProfile saved = subscribers.save(profile);
        String after = summary(saved);
        if (!before.equals(after)) {
            audit.subscriber(saved.getId(), "SUBSCRIBER_UPDATED", "Before: " + before + ". After: " + after);
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
        SubscriberProfile saved = subscribers.save(profile);
        audit.subscriber(saved.getId(), "ENDPOINT_APPROVED", saved.getName() + ": " + saved.getCftEndpoint());
        return toResponse(saved);
    }

    @Override
    public void delete(Long id) {
        SubscriberProfile profile = require(id);
        if (files.existsBySubscriberId(id) || deliveries.existsBySubscriberId(id)) {
            throw new ConflictException("Deactivate this subscriber. History still refers to it.");
        }
        audit.subscriber(id, "SUBSCRIBER_DELETED", summary(profile));
        subscribers.deleteById(id);
    }

    @Override
    public List<String> formats() {
        return FORMATS;
    }

    /**
     * Format, scope, mode, business line and region of a request. A value that is not given keeps the subscriber's
     * current one (XML, ALL and FULL for a new subscriber); one that is given must be a known value.
     */
    private static void applySettings(SubscriberProfile profile, SubscriberRequest request) {
        if (StringUtils.isNotBlank(request.formatType())) {
            List<String> chosen = Arrays.stream(request.formatType().split(",")).map(String::trim).filter(part -> !part.isEmpty())
                    .map(part -> part.toUpperCase(Locale.ROOT)).distinct().toList();
            for (String format : chosen) {
                if (!FORMATS.contains(format)) {
                    throw new BadRequestException("Format " + format + " is not available. Choose from " + String.join(", ", FORMATS));
                }
            }
            profile.setFormatType(String.join(",", chosen));
        }
        profile.setScope(oneOf(request.scope(), SCOPES, profile.getScope(), "Scope"));
        profile.setExportMode(oneOf(request.exportMode(), MODES, profile.getExportMode(), "Mode"));
        profile.setBusinessLine(StringUtils.trimToNull(request.businessLine()));
        profile.setRegion(StringUtils.trimToNull(request.region()));
    }

    private static String oneOf(String value, List<String> allowed, String current, String what) {
        if (StringUtils.isBlank(value)) {
            return current;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new BadRequestException(what + " " + value.trim() + " is not valid. Choose from " + String.join(", ", allowed));
        }
        return upper;
    }

    private SubscriberProfile require(Long id) {
        return subscribers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Subscriber not found"));
    }

    private SubscriberResponse toResponse(SubscriberProfile subscriber) {
        return SubscriberResponse.from(subscriber, templates.templateName(subscriber.getTemplateFamilyId()),
                deliveries.findFirstBySubscriberIdOrderByCreatedAtDesc(subscriber.getId()).orElse(null));
    }

    /** The subscriber in one line, for the audit log. */
    private String summary(SubscriberProfile profile) {
        return profile.getName() + ": " + profile.getFormatType() + ", " + profile.getScope() + ", " + profile.getExportMode()
                + (profile.getBusinessLine() == null ? "" : ", " + profile.getBusinessLine())
                + (profile.getRegion() == null ? "" : ", " + profile.getRegion()) + ", endpoint " + (profile.getCftEndpoint() == null ? "none"
                : profile.getCftEndpoint() + " (" + profile.getEndpointStatus() + ")")
                + ", list " + Objects.toString(templates.templateName(profile.getTemplateFamilyId()), "none")
                + (profile.isActive() ? ", active" : ", inactive");
    }

    /**
     * A delivery endpoint no approved subscriber already uses must be approved by a List Owner first: until then the
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
        if (subscribers.existsByCftEndpointIgnoreCaseAndEndpointStatus(endpoint, "APPROVED")) {
            profile.setEndpointStatus("APPROVED");
        } else {
            profile.setEndpointStatus("PENDING");
            profile.setActive(false);
        }
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
