package com.bnpp.export.dto;

import java.time.LocalDate;
import java.util.List;

public record ExportPreview(
        Long versionId,
        String listCode,
        String listName,
        LocalDate contentAsOf,
        LocalDate fromDate,
        LocalDate toDate,
        boolean demoCatalog,
        String reviewToken,
        List<SubscriberPreview> subscribers
) {
    public record SubscriberPreview(
            Long subscriberId,
            String name,
            String formatType,
            String exportMode,
            String scope,
            String businessLine,
            String region,
            boolean included,
            boolean datesApplied,
            int records,
            int quarantined,
            String outcome
    ) {
    }
}
