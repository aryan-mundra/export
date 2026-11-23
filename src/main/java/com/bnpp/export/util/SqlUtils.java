package com.bnpp.export.util;

import com.bnpp.export.service.SourceCatalogService;

import java.util.Collections;
import java.util.List;

/** Small helpers for building SQL safely: placeholders, LIKE escaping, quoted names and gs_ table lookups. */
public final class SqlUtils {

    private SqlUtils() {
    }

    /** {@code ?, ?, ?} for an IN list of {@code count} values. */
    public static String marks(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    /** Escapes LIKE wildcards; use with {@code ESCAPE '\'}. */
    public static String likeEscape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A quoted identifier, safe for any table or column name read from the database catalog. */
    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Rows of the gs_ table that belong to the list entry {@code alias} and meet the condition. */
    public static String exists(SourceCatalogService.SourceTable table, String alias, String condition) {
        return "EXISTS (SELECT 1 FROM " + table.qualifiedName() + " g WHERE g."
                + quote(table.keyColumn()) + " = " + alias + ".BNP_ID AND " + condition + ")";
    }

    /** A gs_ table a saved list uses; it must still exist and still have its BNP_ID column. */
    public static SourceCatalogService.SourceTable sourceTable(List<SourceCatalogService.SourceTable> tables, String name) {
        SourceCatalogService.SourceTable table = tables.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Source table " + name + " no longer exists"));
        if (table.keyColumn() == null) {
            throw new IllegalStateException(table.name() + " no longer has a " + SourceCatalogService.KEY + " column");
        }
        return table;
    }
}
