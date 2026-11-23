package com.bnpp.export.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** A page of source lists that can be exported (the newest certified version of each). */
public record ListPageResponse(long total, int offset, List<ListSummary> lists) {

    /**
     * @param versions   certified versions of this list operators can export
     * @param lastExport when this list was last exported (null if never)
     */
    public record ListSummary(Long versionId, String listCode, String listName, LocalDate contentAsOf,
                              long versions, LocalDateTime lastExport) {
    }
}
