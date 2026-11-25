package com.bnpp.export.model;

import java.util.Locale;
import java.util.Map;

/**
 * A table group checked against the database: its definition discovered from the foreign keys, and each of its tables as found
 * in the database (real names, schema and columns), keyed by lower-case table name.
 */
public record ResolvedGroup(TableGroup group, Map<String, SourceTable> tables) {

    public SourceTable table(String name) {
        return tables.get(name.toLowerCase(Locale.ROOT));
    }

    /** The real (database) name of a column of one of the group's tables. */
    public String column(String table, String column) {
        return table(table).column(column).map(SourceTable.Column::name).orElse(column);
    }

    /** The group's common key, with its database name. */
    public String keyColumn() {
        return column(group.root().table(), group.root().key());
    }
}
