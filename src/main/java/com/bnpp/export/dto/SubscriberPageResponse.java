package com.bnpp.export.dto;

import java.util.List;

/** A page of active subscribers, as offered when choosing who receives a send. */
public record SubscriberPageResponse(long total, int offset, List<Row> subscribers) {

    public record Row(Long id, String name, String formatType, String scope, String exportMode,
                      String businessLine, String region, Long templateFamilyId, boolean active) {
    }
}
