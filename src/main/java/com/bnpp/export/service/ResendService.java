package com.bnpp.export.service;

import com.bnpp.export.dto.ResendPreviewResponse;
import com.bnpp.export.entity.ExportJob;

import java.util.List;
import java.util.Map;

/** Sending a stored data version again, from its table: the review, then queuing the send. */
public interface ResendService {

    /**
     * What resending a stored data version would send to each chosen subscriber (with no subscribers given, every
     * active subscriber on the version's list), counted from the stored rows.
     *
     * @param formatType one format for every file of this send, or null for each subscriber's own format
     * @param perSubscriber formats per subscriber id (one, or several separated by a comma), overriding {@code formatType}
     */
    ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds, String formatType, Map<Long, String> perSubscriber);

    /** Sends a stored data version again to the chosen subscribers, after a matching review. */
    ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType,
                            Map<Long, String> perSubscriber);

    /** The file formats the module can write, in alphabetical order. */
    List<String> formatTypes();
}
