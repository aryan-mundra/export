package com.bnpp.export.repository;

import com.bnpp.export.entity.FormatField;
import com.bnpp.export.model.GroupRow;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.SourceTable;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.util.SqlUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads the records of a table group from the source database: the rows of its root table, each with its related
 * rows nested under it, following the foreign keys discovered in the source schema.
 *
 * <p>Root rows are read in key order, a batch at a time (keyset paging: no cursor, no OFFSET over large tables).
 * For each batch, every related table is read once per 500 parent values with {@code WHERE foreign_key IN (...)},
 * then attached to its parent rows, level by level. Only the tables a list uses are read, plus the tables between
 * them and the root.
 */
@Repository
public class GroupRecordRepository {

    public static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public GroupRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The number of records (root rows) of a group. */
    public long count(GroupSelection selection) {
        String root = selection.group().root().table();
        List<Object> binds = new ArrayList<>();
        String where = where(predicate(selection, root, "t0", 0, binds));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + qualified(selection, root) + " t0" + where, Long.class, binds.toArray());
        return total == null ? 0 : total;
    }

    /** A page of a group's records in key order, from {@code offset}: for previews. */
    public List<GroupRow> page(GroupSelection selection, int offset, int limit) {
        String root = selection.group().root().table();
        String key = SqlUtils.quote(selection.resolved().keyColumn());
        List<Object> binds = new ArrayList<>();
        String where = where(predicate(selection, root, "t0", 0, binds));
        binds.add(offset);
        binds.add(limit);
        List<GroupRow> rows = query("SELECT " + selectList(selection, root) + " FROM " + qualified(selection, root) + " t0" + where
                + " ORDER BY " + key + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", binds);
        attachChildren(selection, root, rows);
        return rows;
    }

    /** Every record of a group in key order, each with its related rows, read a batch at a time. */
    public void stream(GroupSelection selection, Consumer<GroupRow> consumer) {
        String root = selection.group().root().table();
        String keyColumn = selection.resolved().keyColumn();
        String key = SqlUtils.quote(keyColumn);
        String select = "SELECT " + selectList(selection, root) + " FROM " + qualified(selection, root) + " t0";
        List<Object> filterBinds = new ArrayList<>();
        String filter = predicate(selection, root, "t0", 0, filterBinds);
        Object last = null;
        while (true) {
            List<Object> binds = new ArrayList<>();
            String condition = filter;
            if (last != null) {
                binds.add(last);
                condition = "t0." + key + " > ?" + (filter.isEmpty() ? "" : " AND " + filter);
            }
            binds.addAll(filterBinds);
            List<GroupRow> batch = query(select + where(condition) + " ORDER BY " + key + " FETCH FIRST " + BATCH + " ROWS ONLY", binds);
            if (batch.isEmpty()) {
                return;
            }
            attachChildren(selection, root, batch);
            batch.forEach(consumer);
            last = batch.get(batch.size() - 1).get(keyColumn);
            if (batch.size() < BATCH || last == null) {
                return;
            }
        }
    }

    /** Reads the related tables under {@code parentTable} for these parent rows and attaches them, level by level. */
    private void attachChildren(GroupSelection selection, String parentTable, List<GroupRow> parents) {
        if (parents.isEmpty()) {
            return;
        }
        for (TableGroup.Link link : selection.group().childrenOf(parentTable)) {
            if (!selection.reads(link.table())) {
                continue;
            }
            String parentColumn = selection.column(parentTable, link.refColumn());
            String foreignKey = selection.column(link.table(), link.key());
            // Each distinct parent value is looked up once.
            Map<String, List<GroupRow>> parentsByValue = new LinkedHashMap<>();
            List<Object> values = new ArrayList<>();
            for (GroupRow parent : parents) {
                Object value = parent.get(parentColumn);
                if (value == null) {
                    continue;
                }
                List<GroupRow> sameValue = parentsByValue.get(String.valueOf(value));
                if (sameValue == null) {
                    sameValue = new ArrayList<>();
                    parentsByValue.put(String.valueOf(value), sameValue);
                    values.add(value);
                }
                sameValue.add(parent);
            }
            // Only the rows that keep the list's filters (on this table and, through EXISTS, on the tables below it).
            List<Object> filterBinds = new ArrayList<>();
            String filter = predicate(selection, link.table(), "t0", 0, filterBinds);
            List<GroupRow> children = new ArrayList<>();
            for (int start = 0; start < values.size(); start += BATCH) {
                List<Object> chunk = values.subList(start, Math.min(values.size(), start + BATCH));
                List<Object> binds = new ArrayList<>(chunk);
                binds.addAll(filterBinds);
                children.addAll(query("SELECT " + selectList(selection, link.table()) + " FROM " + qualified(selection, link.table()) + " t0"
                        + " WHERE " + SqlUtils.quote(foreignKey) + " IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")"
                        + (filter.isEmpty() ? "" : " AND " + filter) + " ORDER BY " + SqlUtils.quote(foreignKey), binds));
            }
            String childKey = link.table().toLowerCase(Locale.ROOT);
            for (GroupRow child : children) {
                for (GroupRow parent : parentsByValue.getOrDefault(String.valueOf(child.get(foreignKey)), List.of())) {
                    parent.children().computeIfAbsent(childKey, ignored -> new ArrayList<>()).add(child);
                }
            }
            attachChildren(selection, link.table(), children);
        }
    }

    // ------------------------------------------------------------------ the list's filters

    private static String where(String condition) {
        return condition.isEmpty() ? "" : " WHERE " + condition;
    }

    /**
     * The condition a row of {@code table} (aliased {@code alias}) must meet: each filtered column holds one of the chosen
     * values, and for every table below it that has a filter there is at least one related row that meets that table's
     * condition ({@code EXISTS}): a record with no matching related row is left out. Values are bound in {@code binds}, in
     * the order they appear. The column is compared as trimmed text, the same text the choices were read as.
     */
    private String predicate(GroupSelection selection, String table, String alias, int depth, List<Object> binds) {
        List<String> parts = new ArrayList<>();
        for (FormatField field : selection.filters(table)) {
            List<String> chosen = field.getFilterValues();
            parts.add(textOf(alias + "." + SqlUtils.quote(selection.column(table, field.getSourceColumn())))
                    + " IN (" + String.join(", ", Collections.nCopies(chosen.size(), "?")) + ")");
            binds.addAll(chosen);
        }
        for (TableGroup.Link link : selection.group().childrenOf(table)) {
            if (!selection.reads(link.table()) || !selection.filtersBelow(link.table())) {
                continue;
            }
            String child = "t" + (depth + 1);
            String inner = predicate(selection, link.table(), child, depth + 1, binds);
            parts.add("EXISTS (SELECT 1 FROM " + qualified(selection, link.table()) + " " + child + " WHERE "
                    + child + "." + SqlUtils.quote(selection.column(link.table(), link.key())) + " = "
                    + alias + "." + SqlUtils.quote(selection.column(table, link.refColumn()))
                    + (inner.isEmpty() ? "" : " AND " + inner) + ")");
        }
        return String.join(" AND ", parts);
    }

    private static String textOf(String column) {
        return "TRIM(CAST(" + column + " AS VARCHAR))";
    }

    /** The distinct values of a column as trimmed text (empty and null left out), sorted, to offer as filter choices. */
    public List<String> distinctValues(SourceTable table, String column, String contains, int limit) {
        String text = textOf(SqlUtils.quote(column));
        List<Object> binds = new ArrayList<>();
        String search = "";
        if (contains != null && !contains.isBlank()) {
            search = " AND LOWER(" + text + ") LIKE ? ESCAPE '\\'";
            binds.add("%" + SqlUtils.likeEscape(contains.trim().toLowerCase(Locale.ROOT)) + "%");
        }
        return jdbc.query("SELECT DISTINCT " + text + " AS v FROM " + table.qualifiedName() + " WHERE " + SqlUtils.quote(column)
                + " IS NOT NULL AND " + text + " <> ''" + search + " ORDER BY v FETCH FIRST " + limit + " ROWS ONLY",
                (rs, i) -> rs.getString(1), binds.toArray());
    }

    /** A table's join column, its row id, the columns its children reference, and the list's columns. */
    private static String selectList(GroupSelection selection, String table) {
        TableGroup group = selection.group();
        Set<String> columns = new LinkedHashSet<>();
        columns.add(selection.column(table, group.joinColumn(table)));
        group.link(table).map(TableGroup.Link::id).ifPresent(id -> columns.add(selection.column(table, id)));
        for (TableGroup.Link child : group.childrenOf(table)) {
            if (selection.reads(child.table())) {
                columns.add(selection.column(table, child.refColumn()));
            }
        }
        for (FormatField field : selection.fields(table)) {
            columns.add(selection.column(table, field.getSourceColumn()));
        }
        return String.join(", ", columns.stream().map(SqlUtils::quote).toList());
    }

    private List<GroupRow> query(String sql, List<Object> binds) {
        return jdbc.query(sql, (ResultSet rs, int index) -> toRow(rs), binds.toArray());
    }

    private static GroupRow toRow(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            Object value = rs.getObject(column);
            if (value instanceof Timestamp timestamp) {
                value = timestamp.toLocalDateTime();
            } else if (value instanceof java.sql.Date date) {
                value = date.toLocalDate();
            }
            values.put(meta.getColumnLabel(column).toLowerCase(Locale.ROOT), value);
        }
        return new GroupRow(values, new LinkedHashMap<>());
    }

    private static String qualified(GroupSelection selection, String table) {
        return selection.resolved().table(table).qualifiedName();
    }
}
