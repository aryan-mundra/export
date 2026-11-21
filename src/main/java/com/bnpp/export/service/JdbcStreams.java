package com.bnpp.export.service;

import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads a query row by row with a database cursor, so a result of any size never sits in memory.
 *
 * PostgreSQL only honours the fetch size inside a transaction (auto-commit off); otherwise its
 * driver loads the whole result first. The read gets a connection of its own, so writes made while
 * rows are handled (progress, quarantine, manifest) commit independently. H2 behaves the same way.
 */
public final class JdbcStreams {

    public static final int FETCH_SIZE = 500;

    @FunctionalInterface
    public interface RowHandler {
        void row(ResultSet rs) throws SQLException;
    }

    private JdbcStreams() {
    }

    public static void query(DataSource dataSource, String sql, List<?> args, RowHandler handler) {
        Connection connection = null;
        boolean autoCommit = true;
        try {
            connection = dataSource.getConnection();
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(FETCH_SIZE);
                for (int index = 0; index < args.size(); index++) {
                    statement.setObject(index + 1, args.get(index));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        handler.row(rs);
                    }
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("Reading from the database failed: " + ex.getMessage(), ex);
        } finally {
            if (connection != null) {
                try {
                    connection.rollback();
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // the connection is being returned anyway
                }
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }
}
