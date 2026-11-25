package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A subscriber to create or change.
 *
 * @param formatType the formats it usually receives, comma-separated (default XML)
 * @param scope      ALL, SANCTIONS, WORLDCHECK or PFA (default ALL)
 * @param exportMode FULL or DELTA (default FULL)
 * @param templateId the list (family id) it receives; required when creating
 * @param active     null keeps the current value (active when creating)
 */
public record SubscriberRequest(@NotBlank String name, String formatType, String scope, String exportMode, String businessLine,
                                String region, String cftEndpoint, Long templateId, Boolean active) {
}
