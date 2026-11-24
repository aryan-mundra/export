package com.bnpp.export.service;

import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.SendRequest;
import com.bnpp.export.dto.SendReviewResponse;

import java.util.List;

/**
 * Sending a data version: every chosen subscriber receives the whole version as one XML file. The user reviews the
 * send first; the review token proves the send matches the review.
 */
public interface SendService {

    /** Who would receive the version (default: the active subscribers of its list), and the review token. */
    SendReviewResponse review(Long dataVersionId, List<Long> subscriberIds);

    /** Starts the reviewed send in the background. Refused when the review is out of date. */
    ExportJobResponse send(Long dataVersionId, SendRequest request);
}
