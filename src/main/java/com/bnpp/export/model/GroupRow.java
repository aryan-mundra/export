package com.bnpp.export.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One row of a group table, with its related rows nested under it per table (lower-case table name), in the order
 * of the groups file. A record of a group is its root row.
 */
public record GroupRow(Map<String, Object> values, Map<String, List<GroupRow>> children) {

    /** A column's value, by name (any case). */
    public Object get(String column) {
        return values.get(column.toLowerCase(Locale.ROOT));
    }

    public List<GroupRow> children(String table) {
        return children.getOrDefault(table.toLowerCase(Locale.ROOT), List.of());
    }
}
