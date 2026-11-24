package com.bnpp.export.service;

import com.bnpp.export.dto.DataVersionChangePageResponse;
import com.bnpp.export.dto.DataVersionComparisonSummaryResponse;
import com.bnpp.export.dto.FileDownload;

/**
 * Compares two versions of one list (same list, same source list), in any order: "from" is the before, "to" the
 * after. Everything is computed in SQL on the two versions' own tables, so lists of tens of thousands of records
 * and hundreds of columns stay quick: only a summary and one page of records are ever read.
 */
public interface ComparisonService {

    /** Records added, removed, changed and unchanged, and the changed values per column. */
    DataVersionComparisonSummaryResponse summary(Long toId, Long fromId);

    /**
     * A page of the records that differ, in record order.
     *
     * @param kind   ALL (default), ADDED, REMOVED or CHANGED
     * @param column only records whose value in this column changed (a column name from the summary), or null
     * @param query  only records whose BNP ID contains this text, or null
     */
    DataVersionChangePageResponse changes(Long toId, Long fromId, String kind, String column, String query, int offset, int limit);

    /**
     * Every difference matching the filters as a CSV file, one line per changed value: record id, BNP ID, change,
     * column, value before, value after. Opens in Excel.
     */
    FileDownload changesFile(Long toId, Long fromId, String kind, String column, String query);
}
