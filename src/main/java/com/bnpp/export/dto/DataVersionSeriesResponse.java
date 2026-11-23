package com.bnpp.export.dto;

import java.time.LocalDateTime;

/** One list and source list with the summary of its data versions (a catalog row; no version is read). */
public record DataVersionSeriesResponse(Long familyId, String listCode, String templateName, String listName, int versions,
                                        Long latestId, String latestLabel, LocalDateTime latestAt, long exportsServed,
                                        long totalRows, long storedRows, LocalDateTime lastUsedAt) {
}
