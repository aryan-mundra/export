package com.bnpp.export.dto;

import java.util.List;

/**
 * The preview of one table group of a list: its common key, the list's columns from the group's tables, and a page
 * of records. A related table's column shows every value of the record's related rows, separated by "; ".
 *
 * @param keyColumn the group's common key (each row's {@code key})
 * @param total     the number of records in the group (rows of its root table)
 */
public record GroupPreviewResponse(String group, String rootTable, String keyColumn, List<Column> columns, long total,
                                   int offset, List<Row> rows) {

    /** @param root whether the column is on the root table (otherwise on a related table) */
    public record Column(String table, String column, String header, boolean root) {
    }

    public record Row(String key, List<String> values) {
    }
}
