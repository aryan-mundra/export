package com.bnpp.export.dto;

import com.bnpp.export.entity.ExportJob;

import java.time.LocalDateTime;

/** A send of a data version, as returned when it is started. */
public record ExportJobResponse(Long id, Long familyId, String listName, Long dataVersionId, int dataVersionNumber,
                                String subscriberIds, String triggeredBy, String status, int totalSubscribers, LocalDateTime createdAt) {

    public static ExportJobResponse from(ExportJob job) {
        return new ExportJobResponse(job.getId(), job.getFamilyId(), job.getListName(), job.getDataVersionId(), job.getDataVersionNumber(),
                job.getSubscriberIds(), job.getTriggeredBy(), job.getStatus(), job.getTotalSubscribers(), job.getCreatedAt());
    }
}
