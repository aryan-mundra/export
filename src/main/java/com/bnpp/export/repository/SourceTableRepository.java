package com.bnpp.export.repository;

import com.bnpp.export.exception.ServiceUnavailableException;
import com.bnpp.export.model.SourceTable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Tables of the source database and their columns, read from the database's own catalog (JDBC metadata). */
@Repository
public class SourceTableRepository {

    private final JdbcTemplate jdbc;

    public SourceTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A table or view with its columns: in {@code schema}, or the connection's current schema (the search_path) when
     * null. The name is matched as written, then in lower case (how PostgreSQL stores unquoted names), then upper case.
     */
    public Optional<SourceTable> find(String schema, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.execute((Connection connection) -> {
                DatabaseMetaData meta = connection.getMetaData();
                String owner = schema != null && !schema.isBlank() ? schema.trim() : connection.getSchema();
                String table = name.trim();
                for (String[] candidate : new String[][] {{owner, table},
                        {lower(owner), table.toLowerCase(Locale.ROOT)},
                        {upper(owner), table.toUpperCase(Locale.ROOT)}}) {
                    try (ResultSet rows = meta.getTables(null, candidate[0], candidate[1], new String[] {"TABLE", "VIEW"})) {
                        if (rows.next()) {
                            String foundSchema = rows.getString("TABLE_SCHEM");
                            String foundName = rows.getString("TABLE_NAME");
                            return new SourceTable(foundSchema, foundName, columns(meta, foundSchema, foundName));
                        }
                    }
                }
                return null;
            }));
        } catch (DataAccessException ex) {
            throw new ServiceUnavailableException("The source database could not be read");
        }
    }

    private static List<SourceTable.Column> columns(DatabaseMetaData meta, String schema, String table) throws SQLException {
        List<SourceTable.Column> columns = new ArrayList<>();
        try (ResultSet rows = meta.getColumns(null, schema, table, "%")) {
            while (rows.next()) {
                columns.add(new SourceTable.Column(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"),
                        rows.getInt("ORDINAL_POSITION")));
            }
        }
        columns.sort(Comparator.comparingInt(SourceTable.Column::position));
        return columns;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
