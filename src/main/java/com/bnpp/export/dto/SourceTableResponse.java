package com.bnpp.export.dto;

import java.util.List;

/**
 * A gs_ table offered when building a list.
 *
 * @param keyColumn the table's BNP_ID column, or null when it has none (the table cannot be used)
 * @param joinable  whether the table can be matched to list entries
 */
public record SourceTableResponse(String table, String label, String keyColumn,
                                  boolean joinable, List<Column> columns) {

    /** @param kind text, number, date or other: how the column can be filtered */
    public record Column(String name, String type, String kind) {
    }
}
