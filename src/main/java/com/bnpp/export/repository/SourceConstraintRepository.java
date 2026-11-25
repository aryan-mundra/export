package com.bnpp.export.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.util.List;

/**
 * Reads the primary keys and foreign keys of the source schema from the database's own catalog
 * ({@code information_schema}), so the module learns how the tables relate without a configuration file.
 *
 * <p>One row per constrained column. For a PRIMARY KEY the referenced columns are null; for a FOREIGN KEY they
 * name the table and column it points to. Based on the query of the data model review, with two corrections:
 * the constraint is also matched on its table (constraint names are only unique per table in PostgreSQL), and the
 * referenced column is matched on {@code position_in_unique_constraint} (right for keys of several columns).
 */
@Repository
public class SourceConstraintRepository {

    /** The constraint types the module needs. */
    public static final String PRIMARY_KEY = "PRIMARY KEY";
    public static final String FOREIGN_KEY = "FOREIGN KEY";

    /** One constrained column of a table, and for a foreign key the column it references. */
    public record Constraint(String schema, String table, String column, String name, String type, int position,
                             String refSchema, String refTable, String refColumn) {
    }

    private static final String SQL = """
            SELECT tc.table_schema, tc.table_name, kcu.column_name, tc.constraint_name, tc.constraint_type, kcu.ordinal_position,
                   kcu_ref.table_schema AS ref_schema, kcu_ref.table_name AS ref_table, kcu_ref.column_name AS ref_column
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
               AND tc.table_schema = kcu.table_schema
               AND tc.table_name = kcu.table_name
              LEFT JOIN information_schema.referential_constraints rc
                ON tc.constraint_name = rc.constraint_name
               AND tc.table_schema = rc.constraint_schema
              LEFT JOIN information_schema.key_column_usage kcu_ref
                ON rc.unique_constraint_name = kcu_ref.constraint_name
               AND rc.unique_constraint_schema = kcu_ref.table_schema
               AND kcu.position_in_unique_constraint = kcu_ref.ordinal_position
             WHERE tc.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY')
               AND lower(tc.table_schema) = lower(?)
             ORDER BY tc.table_name, tc.constraint_type, tc.constraint_name, kcu.ordinal_position
            """;

    private final JdbcTemplate jdbc;

    public SourceConstraintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The schema the connection currently uses (PostgreSQL: the first schema of the search_path). */
    public String currentSchema() {
        return jdbc.execute((Connection connection) -> connection.getSchema());
    }

    /** Every primary key and foreign key column of the schema. */
    public List<Constraint> find(String schema) {
        return jdbc.query(SQL, (rs, i) -> new Constraint(rs.getString("table_schema"), rs.getString("table_name"),
                rs.getString("column_name"), rs.getString("constraint_name"), rs.getString("constraint_type"), rs.getInt("ordinal_position"),
                rs.getString("ref_schema"), rs.getString("ref_table"), rs.getString("ref_column")), schema);
    }
}
