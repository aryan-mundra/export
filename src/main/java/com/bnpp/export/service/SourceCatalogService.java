package com.bnpp.export.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Reads the golden-source tables (any table whose name starts with gs_, in any case)
 * from the database: the in-memory H2 database in mock mode, Oracle in the oracle profile.
 */
@Service
public class SourceCatalogService {

    public static final String PREFIX = "gs_";

    /** The key shared by every gs_ table and by each list entry: a gs_ row belongs to the entry with the same BNP_ID. */
    public static final String KEY = "BNP_ID";
    /** The list entry field that holds its BNP_ID. */
    public static final String RECORD_KEY = "bnp_id";

    private final JdbcTemplate jdbcTemplate;
    private final String schema;

    /**
     * @param schema the schema that holds the gs_ tables; empty means the connected user's own schema
     */
    public SourceCatalogService(JdbcTemplate jdbcTemplate,
                                @Value("${export.source.schema:}") String schema) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema == null || schema.isBlank() ? null : schema.trim();
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    public List<SourceTable> tables() {
        try {
            List<SourceTable> tables = jdbc().execute((java.sql.Connection connection) -> {
                DatabaseMetaData meta = connection.getMetaData();
                // Only look in one schema: scanning every schema on a shared Oracle database is slow.
                String owner = schema != null ? schema : connection.getSchema();
                List<SourceTable> found = new ArrayList<>();
                try (ResultSet rows = meta.getTables(null, owner, "%", new String[] {"TABLE", "VIEW"})) {
                    while (rows.next()) {
                        String name = rows.getString("TABLE_NAME");
                        if (name == null || !name.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
                            continue;
                        }
                        String schema = rows.getString("TABLE_SCHEM");
                        found.add(new SourceTable(schema, name, columns(meta, schema, name)));
                    }
                }
                return found;
            });
            if (tables == null) {
                return List.of();
            }
            return tables.stream()
                    .sorted(Comparator.comparing(table -> table.name().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "The source database could not be read");
        }
    }

    public Optional<SourceTable> table(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return tables().stream().filter(table -> table.name().equalsIgnoreCase(name.trim())).findFirst();
    }

    private List<SourceColumn> columns(DatabaseMetaData meta, String schema, String table) throws java.sql.SQLException {
        List<SourceColumn> columns = new ArrayList<>();
        try (ResultSet rows = meta.getColumns(null, schema, table, "%")) {
            while (rows.next()) {
                columns.add(new SourceColumn(
                        rows.getString("COLUMN_NAME"),
                        rows.getString("TYPE_NAME"),
                        rows.getInt("ORDINAL_POSITION")));
            }
        }
        columns.sort(Comparator.comparingInt(SourceColumn::position));
        return columns;
    }

    public record SourceTable(String schema, String name, List<SourceColumn> columns) {

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
            return schema == null || schema.isBlank() ? quote(name) : quote(schema) + "." + quote(name);
        }
    }

    public record SourceColumn(String name, String type, int position) {

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
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
