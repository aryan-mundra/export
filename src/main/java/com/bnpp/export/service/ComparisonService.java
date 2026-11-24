package com.bnpp.export.service;

import com.bnpp.export.dto.DataVersionChangePageResponse;
import com.bnpp.export.dto.DataVersionComparisonSummaryResponse;
import com.bnpp.export.dto.FileDownload;

/**
 * Compares two versions of one list (same list, same source list), in any order: "from" is the before, "to" the
 * after. Records can be added, deleted or updated; within an updated record each value can be added (was empty),
 * deleted (is now empty) or updated; and the list's columns themselves can be added or deleted between list
 * versions. Everything is computed in SQL on the two versions' own tables, so lists of tens of thousands of
 * records and hundreds of columns stay quick: only a summary and one page of records are ever read.
 */
public interface ComparisonService {

    /** Records added, deleted, updated and unchanged, and the added, deleted and updated values per column. */
    DataVersionComparisonSummaryResponse summary(Long toId, Long fromId);

    /**
     * A page of the records that differ, in record order.
     *
     * @param kind     ALL (default), ADDED, REMOVED or CHANGED (records)
     * @param column   only records whose value in this column changed (a column name from the summary), or null
     * @param cellType with a column: only records where that column's value was ADDED, REMOVED or UPDATED; null for any
     * @param query    only records whose BNP ID contains this text, or null
     */
    DataVersionChangePageResponse changes(Long toId, Long fromId, String kind, String column, String cellType, String query,
                                          int offset, int limit);

    /**
     * Every difference matching the filters as a CSV file, one line per changed value: record id, BNP ID, record
     * change, column, column change, value change, value before, value after. Opens in Excel.
     */
    FileDownload changesFile(Long toId, Long fromId, String kind, String column, String cellType, String query);
}
