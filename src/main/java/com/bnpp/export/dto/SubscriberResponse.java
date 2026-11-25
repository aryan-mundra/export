package com.bnpp.export.dto;

import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.SubscriberProfile;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * A subscriber: where its file is delivered, the list it receives, and its last delivery.
 *
 * @param formatType     its usual formats, comma-separated (e.g. {@code XML})
 * @param templateName   the name of its list, or null when none is chosen
 * @param endpointStatus APPROVED, or PENDING until a List Owner approves a new endpoint
 */
public record SubscriberResponse(Long id, String name, String formatType, String scope, String exportMode, String businessLine,
                                 String region, String cftEndpoint, Long templateFamilyId, String templateName,
                                 String endpointStatus, boolean active, String lastDeliveryStatus, String lastFileName,
                                 LocalDateTime lastDeliveredAt, LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** @param lastDelivery its most recent delivery, or null */
    public static SubscriberResponse from(SubscriberProfile subscriber, String templateName, DeliveryRecord lastDelivery) {
        return new SubscriberResponse(subscriber.getId(), subscriber.getName(), subscriber.getFormatType(), subscriber.getScope(),
                subscriber.getExportMode(), subscriber.getBusinessLine(), subscriber.getRegion(), subscriber.getCftEndpoint(), subscriber.getTemplateFamilyId(),
                templateName, subscriber.getEndpointStatus(), subscriber.isActive(),
                lastDelivery == null ? null : lastDelivery.getStatus(),
                lastDelivery == null || lastDelivery.getFilePath() == null ? null : Path.of(lastDelivery.getFilePath()).getFileName().toString(),
                lastDelivery == null ? null : lastDelivery.getDeliveredAt(), subscriber.getCreatedAt(), subscriber.getUpdatedAt());
    }
}
