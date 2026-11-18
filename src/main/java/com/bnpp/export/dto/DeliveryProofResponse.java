package com.bnpp.export.dto;

public record DeliveryProofResponse(
        String result,
        String storedHash,
        String archiveHash,
        Integer formatTemplateVersion,
        String formatType,
        String archiveFileName,
        String message
) {
}
