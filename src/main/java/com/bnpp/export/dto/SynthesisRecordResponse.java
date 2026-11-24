package com.bnpp.export.dto;

import java.util.List;
import java.util.Map;

/** One record in both data versions (null where it is missing), and every difference. */
public record SynthesisRecordResponse(String group, String key, String change, Map<String, Object> before, Map<String, Object> after,
                                      List<Difference> differences) {

    /**
     * @param path   where in the record, e.g. "Address 11" (empty: the record's own values)
     * @param kind   VALUE_CHANGED, ROW_ADDED or ROW_REMOVED
     * @param column the column, for a changed value
     * @param before the old value, or the removed row in words
     * @param after  the new value, or the added row in words
     */
    public record Difference(String path, String table, String kind, String column, String before, String after) {
    }
}
