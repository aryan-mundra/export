package com.bnpp.export.service.impl;

import com.bnpp.export.exception.ServiceUnavailableException;
import com.bnpp.export.service.SourceCatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Implementation of {@link SourceCatalogService}. */
@Service
public class SourceCatalogServiceImpl implements SourceCatalogService {

    private final JdbcTemplate jdbcTemplate;
    private final String schema;

    /**
     * @param schema the schema that holds the gs_ tables; empty means the connected user's own schema
     */
    public SourceCatalogServiceImpl(JdbcTemplate jdbcTemplate,
                                @Value("${export.source.schema:}") String schema) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema == null || schema.isBlank() ? null : schema.trim();
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    @Override
    public List<SourceTable> tables() {
        try {
            List<SourceTable> tables = jdbc().execute((java.sql.Connection connection) -> {
                DatabaseMetaData meta = connection.getMetaData();
                // Only look in one schema: scanning every schema on a shared database is slow.
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
            throw new ServiceUnavailableException("The source database could not be read");
        }
    }

    @Override
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
}
