package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A subscriber to create or change.
 *
 * @param templateId the list (family id) it receives; required when creating
 * @param active     null keeps the current value (active when creating)
 */
public record SubscriberRequest(@NotBlank String name, String cftEndpoint, Long templateId, Boolean active) {
}
