package com.bnpp.export.model;

import com.bnpp.export.util.SqlUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A table or view of the source database, with its columns, as read from the database's own catalog. */
public record SourceTable(String schema, String name, List<Column> columns) {

    /** A column: its name, database type and position in the table. */
    public record Column(String name, String type, int position) {

        /** How the column's values are shown and compared: text, number, date, or other. */
        public String kind() {
            String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
            if (t.contains("DATE") || t.contains("TIMESTAMP")) {
                return "date";
            }
            if (t.contains("INT") || t.contains("NUMERIC") || t.contains("DEC") || t.contains("NUMBER")
                    || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("REAL")) {
                return "number";
            }
            return (t.contains("CHAR") || t.contains("TEXT")) && !t.contains("LOB") ? "text" : "other";
        }
    }

    /** A column by name, ignoring case. */
    public Optional<Column> column(String name) {
        return columns.stream().filter(column -> column.name().equalsIgnoreCase(name)).findFirst();
    }

    /** The table's name for SQL: quoted, with its schema when known. */
    public String qualifiedName() {
        return schema == null || schema.isBlank() ? SqlUtils.quote(name) : SqlUtils.quote(schema) + "." + SqlUtils.quote(name);
    }
}
