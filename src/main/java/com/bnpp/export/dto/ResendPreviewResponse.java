package com.bnpp.export.dto;

import java.util.List;

/**
 * The review of sending a stored data version: what each chosen subscriber would receive. Sending needs
 * {@code reviewToken}, so a send always matches a review the user saw.
 */
public record ResendPreviewResponse(Long snapshotId, String label, String templateName, String listName, long rowCount,
                                    String reviewToken, List<Subscriber> subscribers) {

    /** One subscriber in the review: the format of its file, and the records it would get or that would be held. */
    public record Subscriber(Long subscriberId, String name, String formatType, String scope, String exportMode,
                             long records, long quarantined, String outcome) {
    }
}
