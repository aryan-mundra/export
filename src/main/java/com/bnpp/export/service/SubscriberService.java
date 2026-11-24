package com.bnpp.export.service;

import com.bnpp.export.dto.SubscriberRequest;
import com.bnpp.export.dto.SubscriberResponse;

import java.util.List;

/**
 * Subscribers: who receives a list and where its file is delivered. A new delivery endpoint must be approved by a
 * List Owner before the subscriber receives files.
 */
public interface SubscriberService {

    /** Every subscriber, optionally searched by name, endpoint or list. */
    List<SubscriberResponse> list(String query);

    SubscriberResponse create(SubscriberRequest request);

    SubscriberResponse update(Long id, SubscriberRequest request);

    /** Approves a subscriber's new delivery endpoint and activates the subscriber. */
    SubscriberResponse approveEndpoint(Long id);

    /** Deletes a subscriber without history; one with history must be deactivated instead. */
    void delete(Long id);
}
