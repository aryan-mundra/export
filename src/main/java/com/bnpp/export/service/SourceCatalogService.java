package com.bnpp.export.service;

import com.bnpp.export.util.SqlUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the golden-source tables (any table whose name starts with gs_, in any case)
 * from the database: H2 by default, PostgreSQL in the postgres profile.
 */
public interface SourceCatalogService {

    String PREFIX = "gs_";

    /** The key shared by every gs_ table and by each list entry: a gs_ row belongs to the entry with the same BNP_ID. */
    String KEY = "BNP_ID";

    /** The list entry field that holds its BNP_ID. */
    String RECORD_KEY = "bnp_id";

    JdbcTemplate jdbc();

    List<SourceTable> tables();

    Optional<SourceTable> table(String name);

    record SourceTable(String schema, String name, List<SourceColumn> columns) {

        /** This table's BNP_ID column (any case), or null when the table has none and cannot be used. */
        public String keyColumn() {
            for (SourceColumn column : columns) {
                if (column.name().equalsIgnoreCase(KEY)) {
                    return column.name();
                }
            }
            return null;
        }

        public Optional<SourceColumn> column(String name) {
            return columns.stream().filter(column -> column.name().equalsIgnoreCase(name)).findFirst();
        }

        public String qualifiedName() {
            return schema == null || schema.isBlank() ? SqlUtils.quote(name) : SqlUtils.quote(schema) + "." + SqlUtils.quote(name);
        }
    }

    record SourceColumn(String name, String type, int position) {

        public boolean numeric() {
            String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
            return t.contains("NUMBER") || t.contains("INT") || t.contains("DEC") || t.contains("NUMERIC")
                    || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("REAL");
        }

        public boolean text() {
            String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
            return (t.contains("CHAR") || t.contains("TEXT")) && !t.contains("LOB");
        }

        /** Whether rows can be linked to a record on this column (dates and large objects cannot). */
        public boolean linkable() {
            return numeric() || text();
        }

        public boolean date() {
            String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
            return t.contains("DATE") || t.contains("TIMESTAMP");
        }

        /** How the column can be filtered and sorted: text, number, date, or other (not filterable). */
        public String kind() {
            if (date()) {
                return "date";
            }
            if (numeric()) {
                return "number";
            }
            return text() ? "text" : "other";
        }
    }
}
