package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A data version with its column layout, the row filters of the list version that built it, and where it was used.
 *
 * @param usage      the 50 most recent files built from this version
 * @param usageTotal how many files were built from it in all
 */
public record DataVersionDetailResponse(DataVersionResponse snapshot, List<Map<String, String>> columns, List<String> filters,
                                        List<Usage> usage, long usageTotal) {

    /** One file built from the version. */
    public record Usage(Long jobId, Long statusId, String subscriberName, String formatType, String outcome, String status,
                        int records, String fileName, LocalDateTime at) {
    }
}
