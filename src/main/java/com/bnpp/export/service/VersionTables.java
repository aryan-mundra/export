package com.bnpp.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The physical table of each data version: {@code ver_<list>_v<major>_<minor>}, e.g. ver_list_apac_v1_1.
 *
 * <p>A version's table holds every row it produced, in file order: the list entry fields a subscriber's
 * scope and delta selection need (columns starting with "_"), then one text column per column of the
 * list, named after its column name. Sending a version in any format reads only this table; the source
 * tables are never queried or joined again, so a version stays exactly as it was when it was made.
 *
 * <p>Tables are built under a temporary name ({@code ver_export_stage_...}) and renamed once the version
 * is numbered, so a version is never visible half filled. Leftover stage tables from a crash are dropped
 * at start-up. Names contain only a-z, 0-9 and _, and are always quoted, so a column called "order" or
 * "user" works too.
 */
@Component
public class VersionTables {

    private static final Logger log = LoggerFactory.getLogger(VersionTables.class);

    /** PostgreSQL's identifier limit is 63; four characters are kept spare. */
    private static final int MAX_NAME = 59;
    private static final String STAGE_PREFIX = "ver_export_stage_";

    /** The list entry fields every version table holds, in this order. */
    public static final String[][] ENTRY_COLUMNS = {
            {"_line", "bigint NOT NULL PRIMARY KEY"},
            {"_record_id", "bigint"},
            {"_row_key", "varchar(64) NOT NULL"},
            {"_bnp_id", "varchar(255)"},
            {"_name", "varchar(255)"},
            {"_country", "varchar(255)"},
            {"_record_date", "date"},
            {"_action", "varchar(255)"},
            {"_risk_rating", "varchar(255)"},
            {"_business_line", "varchar(255)"},
            {"_region", "varchar(255)"},
            {"_changed_on", "date"},
            {"_on_sanctions", "boolean NOT NULL"},
            {"_on_worldcheck", "boolean NOT NULL"},
            {"_on_pfa", "boolean NOT NULL"}
    };

    private final JdbcTemplate jdbc;

    public VersionTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ver_list_apac_v1_1 for list "list_apac", version 1.1. */
    public static String tableName(String listName, int major, int minor) {
        String suffix = "_v" + major + "_" + minor;
        String slug = slug(listName, MAX_NAME - 4 - suffix.length());
        return "ver_" + (slug.isEmpty() ? "list" : slug) + suffix;
    }

    /** The table's column for each list column name: lower case, a-z 0-9 _, unique, never starting with "_". */
    public static List<String> columnNames(List<String> headers) {
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String header : headers) {
            String base = slug(header, MAX_NAME - 4);
            if (base.isEmpty()) {
                base = "column";
            }
            if (Character.isDigit(base.charAt(0))) {
                base = "c_" + base;
            }
            String name = base;
            for (int n = 2; !used.add(name); n++) {
                name = base + "_" + n;
            }
            names.add(name);
        }
        return names;
    }

    private static String slug(String text, int max) {
        String value = (text == null ? "" : text).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return value.length() > max ? value.substring(0, max).replaceAll("_+$", "") : value;
    }

    static String quote(String name) {
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Not a version table name: " + name);
        }
        return '"' + name + '"';
    }

    /** Creates an empty table under a temporary name and returns that name. */
    public String createStage(List<String> columns) {
        String stage = STAGE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(stage)).append(" (");
        for (String[] column : ENTRY_COLUMNS) {
            ddl.append(quote(column[0])).append(' ').append(column[1]).append(", ");
        }
        for (String column : columns) {
            ddl.append(quote(column)).append(" text, ");
        }
        ddl.setLength(ddl.length() - 2);
        jdbc.execute(ddl.append(')').toString());
        return stage;
    }

    /** Adds rows: each is the ENTRY_COLUMNS values followed by one value per list column. */
    public void insert(String table, List<String> columns, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quote(table)).append(" (");
        StringBuilder marks = new StringBuilder();
        for (String[] column : ENTRY_COLUMNS) {
            sql.append(quote(column[0])).append(", ");
            marks.append("?, ");
        }
        for (String column : columns) {
            sql.append(quote(column)).append(", ");
            marks.append("?, ");
        }
        sql.setLength(sql.length() - 2);
        marks.setLength(marks.length() - 2);
        jdbc.batchUpdate(sql.append(") VALUES (").append(marks).append(')').toString(), rows);
    }

    /** Gives a filled stage table its version name. */
    public void rename(String stage, String name) {
        jdbc.execute("ALTER TABLE " + quote(stage) + " RENAME TO " + quote(name));
    }

    public void drop(String table) {
        try {
            jdbc.execute("DROP TABLE IF EXISTS " + quote(table));
        } catch (RuntimeException ex) {
            log.warn("Could not drop table {}: {}", table, ex.getMessage());
        }
    }

    public boolean exists(String table) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?",
                Integer.class, table.toLowerCase(Locale.ROOT));
        return found != null && found > 0;
    }

    /** Stage tables left behind by an export that stopped half way. */
    @EventListener(ApplicationReadyEvent.class)
    public void dropLeftoverStages() {
        try {
            for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE LOWER(table_name) LIKE ?",
                    String.class, STAGE_PREFIX + "%")) {
                log.info("Dropping unfinished version table {}", table);
                drop(table.toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not look for unfinished version tables: {}", ex.getMessage());
        }
    }
}
