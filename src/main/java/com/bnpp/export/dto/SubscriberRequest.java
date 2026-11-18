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
        Boolean secretList
) {
}
