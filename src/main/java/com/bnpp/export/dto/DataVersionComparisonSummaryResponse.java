package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The overview of what changed between two versions of a list: how many records were added, removed and
 * changed, and how many values changed in each column. The records themselves are read page by page from
 * {@code .../changes}.
 *
 * @param fromId         the version compared from ("before")
 * @param toId           the version compared to ("after")
 * @param sameLayout     whether both versions have the same columns
 * @param columns        columns in both versions with at least one changed value, most changed first
 * @param addedColumns   columns only in the "to" version (the list's columns changed)
 * @param removedColumns columns only in the "from" version
 */
public record DataVersionComparisonSummaryResponse(Long fromId, String fromLabel, long fromRows, LocalDateTime fromCreatedAt,
                                                   Long toId, String toLabel, long toRows, LocalDateTime toCreatedAt,
                                                   boolean sameLayout, DataVersionComparisonResponse.Counts counts,
                                                   List<ColumnChange> columns, List<String> addedColumns,
                                                   List<String> removedColumns) {

    /**
     * @param entryField true for a field of the list entry itself (name, country, ...) rather than a list column
     * @param changed    records whose value in this column changed
     */
    public record ColumnChange(String column, boolean entryField, long changed) {
    }
}
