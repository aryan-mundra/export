package com.bnpp.export.service;

import com.bnpp.export.dto.SubscriberPageResponse;
import com.bnpp.export.dto.SubscriberRequest;
import com.bnpp.export.dto.SubscriberResponse;

import java.util.List;

/**
 * Subscribers: who receives files, in which formats, which list and where they are delivered. A new or
 * changed delivery endpoint must be approved before the subscriber can receive files.
 */
public interface SubscriberService {

    /** Every subscriber, optionally searched by name, format, scope, mode, business line or region. */
    List<SubscriberResponse> list(String query);

    /** Active subscribers, searched and paged, optionally of one format or one list. */
    SubscriberPageResponse search(String query, String format, Long templateFamilyId, int offset, int limit);

    SubscriberResponse create(SubscriberRequest request);

    SubscriberResponse update(Long id, SubscriberRequest request);

    /** Approves a subscriber's new delivery endpoint and activates the subscriber. */
    SubscriberResponse approveEndpoint(Long id);

    /** Deletes a subscriber that has no history; one with history must be deactivated instead. */
    void delete(Long id);
}
