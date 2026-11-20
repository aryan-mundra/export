package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

public record SubscriberRequest(
        @NotBlank String name,
        @NotBlank String formatType,
        @NotBlank String scope,
        @NotBlank String exportMode,
        String businessLine,
        String region,
        String cftEndpoint,
        Boolean active,
        /** The template (family id) to build this subscriber's files with. Required when creating. */
        Long templateId
) {
}
