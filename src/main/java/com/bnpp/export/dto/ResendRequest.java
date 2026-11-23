package com.bnpp.export.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/** Send a stored data version again: to the chosen subscribers, or (when empty) every active one on its template. */
public record ResendRequest(
        @NotNull Long snapshotId,
        List<Long> subscriberIds,
        String reviewToken,
        /** One format for every file of this send; empty for each subscriber's own format. */
        String formatType,
        /** A format per subscriber id; overrides formatType for that subscriber. */
        Map<Long, String> formats
) {
    public ResendRequest(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType) {
        this(snapshotId, subscriberIds, reviewToken, formatType, null);
    }
}
