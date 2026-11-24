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

/** Implementation of {@link ComparisonService}. */
@Service
public class ComparisonServiceImpl implements ComparisonService {

    private static final Set<String> KINDS = Set.of("ALL", "ADDED", "REMOVED", "CHANGED");
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

    /** A column compared in both versions: its name, and its column in each version's table. */
    private record Column(String name, String toColumn, String fromColumn, boolean entryField) {
    }

    /** The two versions, checked to be comparable. */
    private record Pair(DataSnapshot to, DataSnapshot from) {
        String toTable() {
            return VersionTableRepository.quote(to.getTableName());
        }

        String fromTable() {
            return VersionTableRepository.quote(from.getTableName());
        }
    }

    private record Query(String sql, List<Object> args) {
    }

    // ------------------------------------------------------------------ summary

    @Override
    public DataVersionComparisonSummaryResponse summary(Long toId, Long fromId) {
        Pair pair = pair(toId, fromId);
        List<String> toHeaders = headers(pair.to());
        List<String> fromHeaders = headers(pair.from());
        List<Column> columns = columns(pair);
        List<DataVersionComparisonSummaryResponse.ColumnChange> changed = new ArrayList<>();
        if (!columns.isEmpty()) {
            // One pass over the changed records counts the changed values of every column at once.
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int index = 0; index < columns.size(); index++) {
                Column column = columns.get(index);
                sql.append(index == 0 ? "" : ", ").append("SUM(CASE WHEN ").append(distinct(column)).append(" THEN 1 ELSE 0 END)");
            }
            sql.append(" FROM ").append(pair.toTable()).append(" t JOIN ").append(pair.fromTable())
                    .append(" f ON f.\"_record_id\" = t.\"_record_id\" WHERE f.\"_row_key\" <> t.\"_row_key\"");
            jdbc.query(sql.toString(), rs -> {
                for (int index = 0; index < columns.size(); index++) {
                    long count = rs.getLong(index + 1);
                    if (count > 0) {
                        Column column = columns.get(index);
                        changed.add(new DataVersionComparisonSummaryResponse.ColumnChange(column.name(), column.entryField(), count));
                    }
                }
            });
            changed.sort(Comparator.comparingLong(DataVersionComparisonSummaryResponse.ColumnChange::changed).reversed());
        }
        DataVersionComparisonResponse.Counts counts = snapshots.counts(toId, fromId);
        return new DataVersionComparisonSummaryResponse(
                pair.from().getId(), pair.from().getLabel(), pair.from().getRowCount(), pair.from().getCreatedAt(),
                pair.to().getId(), pair.to().getLabel(), pair.to().getRowCount(), pair.to().getCreatedAt(),
                toHeaders.equals(fromHeaders), counts, changed,
                toHeaders.stream().filter(header -> !fromHeaders.contains(header)).toList(),
                fromHeaders.stream().filter(header -> !toHeaders.contains(header)).toList());
    }

    // ------------------------------------------------------------------ records

    @Override
    public DataVersionChangePageResponse changes(Long toId, Long fromId, String kind, String column, String query, int offset, int limit) {
        Pair pair = pair(toId, fromId);
        Query matching = matching(pair, kind, column, query);
        int size = Math.min(MAX_PAGE, Math.max(1, limit));
        int start = Math.max(0, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + matching.sql() + ") x", Long.class, matching.args().toArray());
        List<DataVersionComparisonResponse.Change> changes = page(pair, matching, column, start, size);
        return new DataVersionChangePageResponse(total == null ? 0 : total, start, size, changes);
    }

    @Override
    public FileDownload changesFile(Long toId, Long fromId, String kind, String column, String query) {
        Pair pair = pair(toId, fromId);
        Query matching = matching(pair, kind, column, query);
        Path file;
        try {
            Path folder = storage.getRoot().resolve("staging");
            Files.createDirectories(folder);
            file = folder.resolve("compare-" + UUID.randomUUID() + ".csv");
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write('﻿'); // lets Excel read the file as UTF-8
                line(out, "record_id", "bnp_id", "change", "column", "value_in_" + pair.from().getLabel(), "value_in_" + pair.to().getLabel());
                for (int offset = 0; ; offset += FILE_BATCH) {
                    List<DataVersionComparisonResponse.Change> batch = page(pair, matching, column, offset, FILE_BATCH);
                    for (DataVersionComparisonResponse.Change change : batch) {
                        if (change.cells().isEmpty()) {
                            line(out, String.valueOf(change.recordId()), change.bnpId(), change.kind(), "", "", "");
                        }
                        for (DataVersionComparisonResponse.CellChange cell : change.cells()) {
                            line(out, String.valueOf(change.recordId()), change.bnpId(), change.kind(), cell.column(), cell.before(), cell.after());
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
    private Query matching(Pair pair, String kind, String column, String query) {
        String type = StringUtils.isBlank(kind) ? "ALL" : kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(type)) {
            throw new BadRequestException("Choose ALL, ADDED, REMOVED or CHANGED");
        }
        Column filter = null;
        if (StringUtils.isNotBlank(column)) {
            filter = columns(pair).stream().filter(candidate -> candidate.name().equals(column)).findFirst()
                    .orElseThrow(() -> new BadRequestException("Unknown column " + column + ": it is not in both versions"));
            type = "CHANGED"; // a column changes only in records present in both versions
        }
        String like = StringUtils.isBlank(query) ? null : "%" + SqlUtils.likeEscape(query.trim().toUpperCase(Locale.ROOT)) + "%";
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (type.equals("ALL") || type.equals("CHANGED")) {
            StringBuilder sql = new StringBuilder("SELECT t.\"_record_id\" AS rid FROM ").append(pair.toTable()).append(" t JOIN ")
                    .append(pair.fromTable()).append(" f ON f.\"_record_id\" = t.\"_record_id\" WHERE f.\"_row_key\" <> t.\"_row_key\"");
            if (filter != null) {
                sql.append(" AND ").append(distinct(filter));
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

    private static String distinct(Column column) {
        return "t." + VersionTableRepository.quote(column.toColumn()) + " IS DISTINCT FROM f." + VersionTableRepository.quote(column.fromColumn());
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
        return new Pair(to, from);
    }

    private List<String> headers(DataSnapshot snapshot) {
        return snapshots.columns(snapshot).stream().map(column -> column.get("header")).toList();
    }

    /** The list's columns present in both versions (matched by name), then the list entry's own fields. */
    private List<Column> columns(Pair pair) {
        Map<String, String> from = new LinkedHashMap<>();
        snapshots.columns(pair.from()).forEach(column -> from.put(column.get("header"), column.get("dbColumn")));
        List<Column> columns = new ArrayList<>();
        for (Map<String, String> column : snapshots.columns(pair.to())) {
            String fromColumn = from.get(column.get("header"));
            if (fromColumn != null && column.get("dbColumn") != null) {
                columns.add(new Column(column.get("header"), column.get("dbColumn"), fromColumn, false));
            }
        }
        for (String[] field : SnapshotService.ENTRY_FIELDS) {
            columns.add(new Column(field[1] + SnapshotService.ENTRY_SUFFIX, "_" + field[0], "_" + field[0], true));
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
