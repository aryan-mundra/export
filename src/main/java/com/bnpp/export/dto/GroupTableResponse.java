package com.bnpp.export.dto;

import java.util.List;

/**
 * A table of a table group, offered when building a list.
 *
 * @param group     its group, e.g. Person
 * @param root      whether it is the group's root table (one record per row)
 * @param keyColumn the column that joins it into the group: the group's key on the root, its foreign key otherwise
 * @param groupKey  the group's common key (on the root table): always on the list, ticked and locked
 * @param joinsTo   for a related table, the "table.column" its foreign key references
 */
public record GroupTableResponse(String table, String group, boolean root, String keyColumn, String groupKey, String joinsTo,
                                 List<Column> columns) {

    /** @param kind text, number, date or other */
    public record Column(String name, String type, String kind) {
    }
}
