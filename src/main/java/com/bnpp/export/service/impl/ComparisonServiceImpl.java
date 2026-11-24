package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DataVersionChangePageResponse;
import com.bnpp.export.dto.DataVersionComparisonResponse;
import com.bnpp.export.dto.DataVersionComparisonSummaryResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.repository.VersionTableRepository;
import com.bnpp.export.service.ComparisonService;
import com.bnpp.export.service.SnapshotService;
import com.bnpp.export.service.StorageService;
import com.bnpp.export.util.SqlUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of {@link ComparisonService}.
 *
 * <p>A value counts as empty when it is missing or blank. A record is updated when at least one of its values was
 * added, deleted or updated (in a column of both versions, or in a column only one version has); the same rule
 * gives the counts, the column summary and the filters, so they always agree.
 */
@Service
public class ComparisonServiceImpl implements ComparisonService {

    private static final Set<String> KINDS = Set.of("ALL", "ADDED", "REMOVED", "CHANGED");
    private static final Set<String> CELL_TYPES = Set.of("ADDED", "REMOVED", "UPDATED");
    /** List entry fields holding text (the others are dates and flags, empty only when missing). */
    private static final Set<String> TEXT_ENTRY_FIELDS = Set.of("name", "country", "action", "risk_rating", "business_line", "region");
    private static final String BOTH = "BOTH";
    private static final int MAX_PAGE = 200;
    private static final int FILE_BATCH = 500;

    private final SnapshotService snapshots;
    private final StorageService storage;
    private final JdbcTemplate jdbc;

    public ComparisonServiceImpl(SnapshotService snapshots, StorageService storage, JdbcTemplate jdbc) {
        this.snapshots = snapshots;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    /**
     * A column compared: its name, its column in each version's table (null when that version does not have it),
     * BOTH / ADDED / REMOVED, and whether it holds text (blank counts as empty).
     */
    private record Column(String name, String toColumn, String fromColumn, String status, boolean entryField, boolean text) {

        private static String ref(String alias, String column) {
            return alias + "." + VersionTableRepository.quote(column);
        }

        private String empty(String alias, String column) {
            String ref = ref(alias, column);
            return text ? "(" + ref + " IS NULL OR " + ref + " = '')" : ref + " IS NULL";
        }

        /** A value was added: none before, one now. */
        String added() {
            if (fromColumn == null) {
                return "NOT " + empty("t", toColumn);
            }
            return toColumn == null ? "1 = 0" : empty("f", fromColumn) + " AND NOT " + empty("t", toColumn);
        }

        /** A value was deleted: one before, none now. */
        String removed() {
            if (toColumn == null) {
                return "NOT " + empty("f", fromColumn);
            }
            return fromColumn == null ? "1 = 0" : "NOT " + empty("f", fromColumn) + " AND " + empty("t", toColumn);
        }

        /** A value was replaced by another. */
        String updated() {
            if (toColumn == null || fromColumn == null) {
                return "1 = 0";
            }
            return "NOT " + empty("f", fromColumn) + " AND NOT " + empty("t", toColumn) + " AND "
                    + ref("t", toColumn) + " <> " + ref("f", fromColumn);
        }

        String changed() {
            return "(" + added() + ") OR (" + removed() + ") OR (" + updated() + ")";
        }

        String of(String cellType) {
            return switch (cellType) {
                case "ADDED" -> added();
                case "REMOVED" -> removed();
                case "UPDATED" -> updated();
                default -> changed();
            };
        }
    }

    /** The two versions, checked to be comparable, with their compared columns. */
    private record Pair(DataSnapshot to, DataSnapshot from, List<Column> columns) {
        String toTable() {
            return VersionTableRepository.quote(to.getTableName());
        }

        String fromTable() {
            return VersionTableRepository.quote(from.getTableName());
        }

        /** Records in both versions whose values differ (the stored row hash tells unchanged records apart first). */
        String updatedRecords() {
            StringBuilder any = new StringBuilder();
            for (Column column : columns) {
                any.append(any.length() == 0 ? "" : " OR ").append("(").append(column.changed()).append(")");
            }
            return " FROM " + toTable() + " t JOIN " + fromTable() + " f ON f.\"_record_id\" = t.\"_record_id\""
                    + " WHERE f.\"_row_key\" <> t.\"_row_key\"" + (any.length() == 0 ? " AND 1 = 0" : " AND (" + any + ")");
        }
    }

    private record Query(String sql, List<Object> args) {
    }

    // ------------------------------------------------------------------ summary

    @Override
    public DataVersionComparisonSummaryResponse summary(Long toId, Long fromId) {
        Pair pair = pair(toId, fromId);
        List<Column> columns = pair.columns();
        // One pass over the updated records counts, for every column, the values added, deleted and updated.
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)");
        for (Column column : columns) {
            sql.append(", SUM(CASE WHEN ").append(column.added()).append(" THEN 1 ELSE 0 END)")
                    .append(", SUM(CASE WHEN ").append(column.removed()).append(" THEN 1 ELSE 0 END)")
                    .append(", SUM(CASE WHEN ").append(column.updated()).append(" THEN 1 ELSE 0 END)");
        }
        sql.append(pair.updatedRecords());
        long[] updated = new long[1];
        List<DataVersionComparisonSummaryResponse.ColumnChange> changed = new ArrayList<>();
        jdbc.query(sql.toString(), rs -> {
            updated[0] = rs.getLong(1);
            for (int index = 0; index < columns.size(); index++) {
                long added = rs.getLong(2 + index * 3);
                long removed = rs.getLong(3 + index * 3);
                long replaced = rs.getLong(4 + index * 3);
                Column column = columns.get(index);
                if (added + removed + replaced > 0) {
                    changed.add(new DataVersionComparisonSummaryResponse.ColumnChange(column.name(), column.status(),
                            column.entryField(), added + removed + replaced, added, removed, replaced));
                }
            }
        });
        changed.sort(Comparator.comparingLong(DataVersionComparisonSummaryResponse.ColumnChange::changed).reversed());
        long inserted = count("SELECT COUNT(*) FROM (" + onlyIn(pair.toTable(), pair.fromTable(), null, new ArrayList<>()) + ") x");
        long deleted = count("SELECT COUNT(*) FROM (" + onlyIn(pair.fromTable(), pair.toTable(), null, new ArrayList<>()) + ") x");
        long both = count("SELECT COUNT(*) FROM " + pair.toTable() + " t JOIN " + pair.fromTable() + " f ON f.\"_record_id\" = t.\"_record_id\"");
        List<String> added = columns.stream().filter(column -> "ADDED".equals(column.status())).map(Column::name).toList();
        List<String> removed = columns.stream().filter(column -> "REMOVED".equals(column.status())).map(Column::name).toList();
        return new DataVersionComparisonSummaryResponse(
                pair.from().getId(), pair.from().getLabel(), pair.from().getRowCount(), pair.from().getCreatedAt(),
                pair.to().getId(), pair.to().getLabel(), pair.to().getRowCount(), pair.to().getCreatedAt(),
                added.isEmpty() && removed.isEmpty(),
                new DataVersionComparisonResponse.Counts(inserted, deleted, updated[0], both - updated[0]),
                changed, added, removed);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ records

    @Override
    public DataVersionChangePageResponse changes(Long toId, Long fromId, String kind, String column, String cellType, String query,
                                                 int offset, int limit) {
        Pair pair = pair(toId, fromId);
        Query matching = matching(pair, kind, column, cellType, query);
        int size = Math.min(MAX_PAGE, Math.max(1, limit));
        int start = Math.max(0, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + matching.sql() + ") x", Long.class, matching.args().toArray());
        List<DataVersionComparisonResponse.Change> changes = page(pair, matching, column, start, size);
        return new DataVersionChangePageResponse(total == null ? 0 : total, start, size, changes);
    }

    @Override
    public FileDownload changesFile(Long toId, Long fromId, String kind, String column, String cellType, String query) {
        Pair pair = pair(toId, fromId);
        Query matching = matching(pair, kind, column, cellType, query);
        Path file;
        try {
            Path folder = storage.getRoot().resolve("staging");
            Files.createDirectories(folder);
            file = folder.resolve("compare-" + UUID.randomUUID() + ".csv");
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write('﻿'); // lets Excel read the file as UTF-8
                line(out, "record_id", "bnp_id", "record_change", "column", "column_change", "value_change",
                        "value_in_" + pair.from().getLabel(), "value_in_" + pair.to().getLabel());
                for (int offset = 0; ; offset += FILE_BATCH) {
                    List<DataVersionComparisonResponse.Change> batch = page(pair, matching, column, offset, FILE_BATCH);
                    for (DataVersionComparisonResponse.Change change : batch) {
                        String record = word(change.kind());
                        if (change.cells().isEmpty()) {
                            line(out, String.valueOf(change.recordId()), change.bnpId(), record, "", "", "", "", "");
                        }
                        for (DataVersionComparisonResponse.CellChange cell : change.cells()) {
                            line(out, String.valueOf(change.recordId()), change.bnpId(), record, cell.column(),
                                    cell.columnStatus() == null ? "" : word(cell.columnStatus()), word(cell.type()), cell.before(), cell.after());
                        }
                    }
                    if (batch.size() < FILE_BATCH) {
                        break;
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not write the comparison file", ex);
        }
        String name = (pair.to().getTemplateName() + "_" + pair.to().getListCode() + "_changes_" + pair.from().getLabel()
                + "_to_" + pair.to().getLabel()).replaceAll("[^A-Za-z0-9._-]+", "_") + ".csv";
        return FileDownload.temporary(name, file);
    }

    /** The words the screen uses: ADDED, DELETED, UPDATED. */
    private static String word(String type) {
        return switch (type) {
            case "REMOVED" -> "DELETED";
            case "CHANGED" -> "UPDATED";
            default -> type;
        };
    }

    /** One page of matching records, in record order, with their changes; a filtered column's change comes first. */
    private List<DataVersionComparisonResponse.Change> page(Pair pair, Query matching, String column, int offset, int size) {
        List<Object> args = new ArrayList<>(matching.args());
        args.add(offset);
        args.add(size);
        List<Long> ids = jdbc.queryForList("SELECT rid FROM (" + matching.sql() + ") x ORDER BY rid OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                Long.class, args.toArray());
        List<DataVersionComparisonResponse.Change> changes = snapshots.describeChanges(pair.to(), pair.from(), ids);
        if (StringUtils.isBlank(column)) {
            return changes;
        }
        return changes.stream().map(change -> {
            List<DataVersionComparisonResponse.CellChange> cells = new ArrayList<>(change.cells());
            cells.sort(Comparator.comparing(cell -> !column.equals(cell.column())));
            return new DataVersionComparisonResponse.Change(change.recordId(), change.bnpId(), change.kind(), cells);
        }).toList();
    }

    /** The record ids that differ between the two versions and pass the filters. */
    private Query matching(Pair pair, String kind, String column, String cellType, String query) {
        String type = StringUtils.isBlank(kind) ? "ALL" : kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(type)) {
            throw new BadRequestException("Choose ALL, ADDED, REMOVED or CHANGED");
        }
        String cell = StringUtils.isBlank(cellType) ? null : cellType.trim().toUpperCase(Locale.ROOT);
        if (cell != null && !CELL_TYPES.contains(cell)) {
            throw new BadRequestException("Choose ADDED, REMOVED or UPDATED values");
        }
        Column filter = null;
        if (StringUtils.isNotBlank(column)) {
            filter = pair.columns().stream().filter(candidate -> candidate.name().equals(column)).findFirst()
                    .orElseThrow(() -> new BadRequestException("Unknown column " + column));
            type = "CHANGED"; // a value changes only in records present in both versions
        }
        String like = StringUtils.isBlank(query) ? null : "%" + SqlUtils.likeEscape(query.trim().toUpperCase(Locale.ROOT)) + "%";
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (type.equals("ALL") || type.equals("CHANGED")) {
            StringBuilder sql = new StringBuilder("SELECT t.\"_record_id\" AS rid").append(pair.updatedRecords());
            if (filter != null) {
                sql.append(" AND (").append(filter.of(cell == null ? "ANY" : cell)).append(")");
            }
            if (like != null) {
                sql.append(" AND UPPER(t.\"_bnp_id\") LIKE ? ESCAPE '\\'");
                args.add(like);
            }
            parts.add(sql.toString());
        }
        if (type.equals("ALL") || type.equals("ADDED")) {
            parts.add(onlyIn(pair.toTable(), pair.fromTable(), like, args));
        }
        if (type.equals("ALL") || type.equals("REMOVED")) {
            parts.add(onlyIn(pair.fromTable(), pair.toTable(), like, args));
        }
        return new Query(String.join(" UNION ALL ", parts), args);
    }

    /** Records of {@code table} with no record of the same id in {@code other}. */
    private static String onlyIn(String table, String other, String like, List<Object> args) {
        StringBuilder sql = new StringBuilder("SELECT a.\"_record_id\" AS rid FROM ").append(table)
                .append(" a WHERE NOT EXISTS (SELECT 1 FROM ").append(other).append(" b WHERE b.\"_record_id\" = a.\"_record_id\")");
        if (like != null) {
            sql.append(" AND UPPER(a.\"_bnp_id\") LIKE ? ESCAPE '\\'");
            args.add(like);
        }
        return sql.toString();
    }

    // ------------------------------------------------------------------ helpers

    private Pair pair(Long toId, Long fromId) {
        if (Objects.equals(toId, fromId)) {
            throw new BadRequestException("Choose two different versions to compare");
        }
        DataSnapshot to = snapshots.get(toId);
        DataSnapshot from = snapshots.get(fromId);
        if (!to.getFamilyId().equals(from.getFamilyId()) || !to.getListCode().equals(from.getListCode())) {
            throw new BadRequestException("Only versions of the same list and source list can be compared");
        }
        return new Pair(to, from, columns(to, from));
    }

    /**
     * The list's columns, matched by name: those of the "to" version (in both, or new), then those only the "from"
     * version has (deleted), then the list entry's own fields.
     */
    private List<Column> columns(DataSnapshot to, DataSnapshot from) {
        Map<String, String> fromColumns = new LinkedHashMap<>();
        snapshots.columns(from).forEach(column -> fromColumns.put(column.get("header"), column.get("dbColumn")));
        Map<String, String> toColumns = new LinkedHashMap<>();
        snapshots.columns(to).forEach(column -> toColumns.put(column.get("header"), column.get("dbColumn")));
        List<Column> columns = new ArrayList<>();
        toColumns.forEach((header, dbColumn) -> {
            String fromColumn = fromColumns.get(header);
            columns.add(new Column(header, dbColumn, fromColumn, fromColumn == null ? "ADDED" : BOTH, false, true));
        });
        fromColumns.forEach((header, dbColumn) -> {
            if (!toColumns.containsKey(header)) {
                columns.add(new Column(header, null, dbColumn, "REMOVED", false, true));
            }
        });
        for (String[] field : SnapshotService.ENTRY_FIELDS) {
            columns.add(new Column(field[1] + SnapshotService.ENTRY_SUFFIX, "_" + field[0], "_" + field[0], BOTH, true,
                    TEXT_ENTRY_FIELDS.contains(field[0])));
        }
        return columns;
    }

    private static void line(BufferedWriter out, String... values) throws IOException {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                out.write(',');
            }
            String value = values[index] == null ? "" : values[index];
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                out.write('"');
                out.write(value.replace("\"", "\"\""));
                out.write('"');
            } else {
                out.write(value);
            }
        }
        out.write("\r\n");
    }
}
