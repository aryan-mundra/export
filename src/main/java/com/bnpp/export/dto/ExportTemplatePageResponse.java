package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

/** A page of approved lists, with their latest data version and subscribers. */
public record ExportTemplatePageResponse(long total, int offset, List<Row> templates) {

    /**
     * One approved list.
     *
     * @param latestSnapshotId its newest data version (null when it was never exported); listCode tells which source list
     * @param versionCount     data versions it has across all its source lists
     */
    public record Row(Long familyId, Long templateId, String name, int templateVersion,
                      long subscribers, Long latestSnapshotId, String latestLabel, LocalDateTime latestAt,
                      String listCode, String listName, long versionCount, LocalDateTime lastExport) {
    }
}
