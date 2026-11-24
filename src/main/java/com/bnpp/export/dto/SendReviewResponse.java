package com.bnpp.export.dto;

import java.util.List;

/**
 * What sending a data version would do: one XML file of the whole version per subscriber.
 *
 * @param defaultSubscribers the active subscribers of the version's list (chosen when none are given)
 * @param reviewToken        to pass to the send
 */
public record SendReviewResponse(DataVersionResponse version, List<Row> subscribers, List<Long> defaultSubscribers, String reviewToken) {

    /** @param onThisList whether the subscriber chose this list */
    public record Row(Long subscriberId, String name, long records, String endpoint, boolean onThisList) {
    }
}
