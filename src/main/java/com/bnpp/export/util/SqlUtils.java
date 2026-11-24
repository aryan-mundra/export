package com.bnpp.export.util;

/** Small helpers for building SQL safely: LIKE escaping and quoted names. */
public final class SqlUtils {

    private SqlUtils() {
    }

    /** Escapes LIKE wildcards; use with {@code ESCAPE '\'}. */
    public static String likeEscape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A quoted identifier, safe for any table or column name read from the database catalog. */
    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
