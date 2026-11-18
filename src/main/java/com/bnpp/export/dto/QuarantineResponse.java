package com.bnpp.export.dto;

import java.time.LocalDateTime;

public record QuarantineResponse(
        Long id,
        Long versionId,
        Long exportJobId,
        Long subscriberId,
        String subscriberName,
        String recordIdentifier,
        String reason,
        boolean patchApplied,
        String acceptedBy,
        LocalDateTime acceptedAt,
        LocalDateTime createdAt
) {
}
