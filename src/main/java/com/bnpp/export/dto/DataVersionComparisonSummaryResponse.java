package com.bnpp.export.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The overview of what changed between two versions of a list: records added, deleted and updated, and per column
 * how many values were added, deleted and updated, including columns that exist in only one of the versions. The
 * records themselves are read page by page from {@code .../changes}.
 *
 * @param fromId         the version compared from ("before")
 * @param toId           the version compared to ("after")
 * @param sameLayout     whether both versions have the same columns
 * @param counts         records: inserted (added), deleted, changed (updated: at least one value differs), unchanged
 * @param columns        columns with at least one changed value, most changed first
 * @param addedColumns   columns only in the "to" version (the list gained them)
 * @param removedColumns columns only in the "from" version (the list dropped them)
 */
public record DataVersionComparisonSummaryResponse(Long fromId, String fromLabel, long fromRows, LocalDateTime fromCreatedAt,
                                                   Long toId, String toLabel, long toRows, LocalDateTime toCreatedAt,
                                                   boolean sameLayout, DataVersionComparisonResponse.Counts counts,
                                                   List<ColumnChange> columns, List<String> addedColumns,
                                                   List<String> removedColumns) {

    /**
     * How one column changed, counted over the records in both versions.
     *
     * @param status     BOTH (in both versions), ADDED (only in "to": a new column) or REMOVED (only in "from")
     * @param entryField true for a field of the list entry itself (name, country, ...) rather than a list column
     * @param changed    records whose value in this column changed (added + removed + updated)
     * @param added      records that had no value here and now have one
     * @param removed    records that had a value here and now have none
     * @param updated    records whose value here was replaced by another
     */
    public record ColumnChange(String column, String status, boolean entryField, long changed, long added, long removed,
                               long updated) {
    }
}
