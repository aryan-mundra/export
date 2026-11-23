package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DataVersionComparisonResponse;
import com.bnpp.export.dto.DataVersionPageResponse;
import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.DataVersionRowsResponse;
import com.bnpp.export.dto.DataVersionSeriesPageResponse;
import com.bnpp.export.dto.DataVersionSeriesResponse;
import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.format.RecordBatchProvider;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.repository.DataSnapshotRepository;
import com.bnpp.export.repository.VersionTableRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.GoldenSourceService;
import com.bnpp.export.service.RowFilterService;
import com.bnpp.export.service.SnapshotService;
import com.bnpp.export.service.SourceCatalogService;
import com.bnpp.export.service.StorageService;
import com.bnpp.export.util.JdbcStreams;
import com.bnpp.export.util.SqlUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** Implementation of {@link SnapshotService}. */
@Service
public class SnapshotServiceImpl implements SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 500;

    /** Entry fields read with each row of a version table, in the order {@link #record} expects them. */
    private static final String ENTRY_SELECT = "\"_record_id\", \"_bnp_id\", \"_name\", \"_country\", \"_record_date\", \"_action\", "
            + "\"_risk_rating\", \"_business_line\", \"_region\", \"_changed_on\", \"_on_sanctions\", \"_on_worldcheck\", \"_on_pfa\"";

    private final DataSnapshotRepository snapshots;
    private final VersionTableRepository tables;
    private final CertifiedVersionRepository versions;
    private final FormatTemplateService templates;
    private final RowFilterService rowFilters;
    private final GoldenSourceService enricher;
    private final RecordBatchProvider records;
    private final StorageService storage;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final TransactionTemplate separateTransaction;

    public SnapshotServiceImpl(DataSnapshotRepository snapshots,
                           CertifiedVersionRepository versions,
                           FormatTemplateService templates,
                           RowFilterService rowFilters,
                           GoldenSourceService enricher,
                           RecordBatchProvider records,
                           StorageService storage,
                           AuditService audit,
                           JdbcTemplate jdbc,
                           DataSource dataSource,
                           PlatformTransactionManager transactionManager,
                           VersionTableRepository tables) {
        this.snapshots = snapshots;
        this.tables = tables;
        this.versions = versions;
        this.templates = templates;
        this.rowFilters = rowFilters;
        this.enricher = enricher;
        this.records = records;
        this.storage = storage;
        this.audit = audit;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public DataVersionSeriesPageResponse series(String query, Long familyId, String sort, int offset, int limit) {
        StringBuilder where = new StringBuilder(" FROM ver_export_snapshot_series WHERE version_count > 0");
        List<Object> args = new ArrayList<>();
        if (familyId != null) {
            where.append(" AND family_id = ?");
            args.add(familyId);
        }
        String text = query == null ? "" : query.trim().toUpperCase();
        if (!text.isEmpty()) {
            where.append(" AND (UPPER(template_name) LIKE ? ESCAPE '\\' OR UPPER(list_name) LIKE ? ESCAPE '\\' OR UPPER(list_code) LIKE ? ESCAPE '\\')");
            String like = "%" + SqlUtils.likeEscape(text) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        String order = "name".equalsIgnoreCase(sort)
                ? " ORDER BY template_name, list_name, id"
                : " ORDER BY last_used_at DESC NULLS LAST, id DESC";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.max(0, offset));
        pageArgs.add(Math.min(200, Math.max(1, limit)));
        List<DataVersionSeriesResponse> rows = jdbc.query("SELECT family_id, list_code, template_name, list_name, version_count, latest_snapshot_id, "
                        + "latest_label, latest_at, exports_served, total_rows, stored_rows, last_used_at" + where + order
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, index) -> new DataVersionSeriesResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getLong(6), rs.getString(7), toLocal(rs.getTimestamp(8)), rs.getLong(9), rs.getLong(10), rs.getLong(11),
                        toLocal(rs.getTimestamp(12))),
                pageArgs.toArray());
        return new DataVersionSeriesPageResponse(total == null ? 0 : total, Math.max(0, offset), rows);
    }

    @Override
    public DataVersionSeriesResponse seriesOf(Long familyId, String listCode) {
        List<DataVersionSeriesResponse> found = jdbc.query("SELECT family_id, list_code, template_name, list_name, version_count, latest_snapshot_id, "
                        + "latest_label, latest_at, exports_served, total_rows, stored_rows, last_used_at FROM ver_export_snapshot_series "
                        + "WHERE family_id = ? AND list_code = ?",
                (rs, index) -> new DataVersionSeriesResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getLong(6), rs.getString(7), toLocal(rs.getTimestamp(8)), rs.getLong(9), rs.getLong(10), rs.getLong(11),
                        toLocal(rs.getTimestamp(12))),
                familyId, listCode);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("No data versions for this list and source list");
        }
        return found.get(0);
    }

    @Override
    public DataVersionPageResponse versions(Long familyId, String listCode, int offset, int limit) {
        int size = Math.min(100, Math.max(1, limit));
        int start = Math.max(0, offset);
        org.springframework.data.domain.Page<DataSnapshot> page = snapshots.findByFamilyIdAndListCodeOrderByIdDesc(
                familyId, listCode, org.springframework.data.domain.PageRequest.of(start / size, size));
        return new DataVersionPageResponse(seriesOf(familyId, listCode), page.getTotalElements(), start,
                page.getContent().stream().map(DataVersionResponse::from).toList());
    }

    private static LocalDateTime toLocal(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    @Override
    public DataSnapshot get(Long id) {
        return snapshots.findById(id).orElseThrow(() -> new ResourceNotFoundException("Data version not found"));
    }

    // ------------------------------------------------------------------ building

    @Override
    public Resolution resolve(Long certifiedVersionId, FormatTemplate template, Long jobId) {
        CertifiedVersion version = versions.findByVersionId(certifiedVersionId)
                .orElseThrow(() -> new IllegalStateException("Certified version " + certifiedVersionId + " was not found"));
        List<FormatField> fields = templates.fields(template.getId());
        if (fields.isEmpty()) {
            throw new IllegalStateException("List " + template.getName() + " v" + template.getVersionNumber() + " has no columns");
        }
        String columnsJson = toJson(columns(fields));
        RowFilterService.Condition condition = rowFilters.condition(templates.filters(template.getId()), "e");
        String keyPrefix = template.getFamilyId() + "|" + version.getListCode() + "|";

        Path staging = stagingFile();
        MessageDigest dataset = sha256();
        dataset.update(columnsJson.getBytes(StandardCharsets.UTF_8));
        long[] count = new long[1];
        try {
            try (BufferedWriter out = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
                records.stream(certifiedVersionId, "ALL", "FULL", null, null, BATCH, batch -> {
                    List<Map<String, Object>> kept = rowFilters.keep(certifiedVersionId, batch, condition);
                    for (Map<String, Object> record : enricher.enrich(kept, fields)) {
                        String row = toJson(canonical(record, fields));
                        String key = hex(sha256().digest((keyPrefix + row).getBytes(StandardCharsets.UTF_8)));
                        dataset.update(key.getBytes(StandardCharsets.US_ASCII));
                        try {
                            out.write(key);
                            out.write('\t');
                            out.write(row);
                            out.write('\n');
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                        count[0]++;
                    }
                });
            }
            String fingerprint = hex(dataset.digest());
            return register(template, version, columnsJson, fingerprint, count[0], staging, jobId);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not stage the data version: " + ex.getMessage(), ex);
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // removed at the next start-up
            }
        }
    }

    /**
     * Numbers and stores a dataset under a lock on its template and list, or finds it already stored.
     *
     * <p>New data is first copied into a stage table (outside the lock, as it can be large). Under the
     * lock the version is numbered, and the stage table is renamed to the version's table as the last
     * step, so a version is never visible without its complete table.
     */
    private Resolution register(FormatTemplate template, CertifiedVersion version, String columnsJson, String fingerprint,
                                long rowCount, Path staging, Long jobId) {
        Long familyId = template.getFamilyId();
        String listCode = version.getListCode();
        ensureSeries(familyId, listCode);
        boolean known = snapshots.findFirstByFamilyIdAndListCodeAndTemplateIdAndFingerprint(
                familyId, listCode, template.getId(), fingerprint).isPresent();
        List<String> dbColumns = dbColumns(columnsJson);
        String stage = known ? null : tables.createStage(dbColumns);
        Resolution resolution;
        try {
            if (stage != null) {
                fillStage(stage, dbColumns, staging);
            }
            resolution = transaction.execute(status -> {
                // Serialises numbering for this template and list; other templates and lists are not blocked.
                jdbc.queryForList("SELECT id FROM ver_export_snapshot_series WHERE family_id = ? AND list_code = ? FOR UPDATE",
                        familyId, listCode);
                DataSnapshot same = snapshots.findFirstByFamilyIdAndListCodeAndTemplateIdAndFingerprint(
                        familyId, listCode, template.getId(), fingerprint).orElse(null);
                if (same != null || stage == null) {
                    if (same == null) {
                        throw new IllegalStateException("Data version disappeared while it was being reused");
                    }
                    same.setReuseCount(same.getReuseCount() + 1);
                    same.setLastUsedAt(LocalDateTime.now());
                    jdbc.update("UPDATE ver_export_snapshot_series SET exports_served = COALESCE(exports_served, 0) + 1, last_used_at = ? "
                            + "WHERE family_id = ? AND list_code = ?", same.getLastUsedAt(), familyId, listCode);
                    return new Resolution(snapshots.save(same), false);
                }
                Integer minor = snapshots.maxMinor(familyId, listCode, template.getVersionNumber());
                DataSnapshot previous = snapshots.findFirstByFamilyIdAndListCodeOrderByIdDesc(familyId, listCode).orElse(null);

                DataSnapshot snapshot = new DataSnapshot();
                snapshot.setFamilyId(familyId);
                snapshot.setListCode(listCode);
                snapshot.setListName(version.getListName());
                snapshot.setTemplateId(template.getId());
                snapshot.setTemplateName(template.getName());
                snapshot.setMajor(template.getVersionNumber());
                // The first data version of template version N is N.1, then N.2, N.3 ...
                snapshot.setMinor(minor == null ? 1 : minor + 1);
                snapshot.setLabel(snapshot.getMajor() + "." + snapshot.getMinor());
                snapshot.setTableName(freeTableName(template.getName(), snapshot.getMajor(), snapshot.getMinor(), listCode));
                snapshot.setSourceVersionId(version.getVersionId());
                snapshot.setFingerprint(fingerprint);
                snapshot.setRowCount(rowCount);
                snapshot.setStoredRows(rowCount);
                snapshot.setColumnsJson(columnsJson);
                snapshot.setCreatedBy(CurrentUser.actor());
                snapshot.setCreatedByName(CurrentUser.displayName());
                snapshot.setCreatedJobId(jobId);
                if (previous != null) {
                    DataVersionComparisonResponse.Counts counts = counts(stage, previous.getTableName());
                    snapshot.setPreviousSnapshotId(previous.getId());
                    snapshot.setPreviousLabel(previous.getLabel());
                    snapshot.setInsertedRows(counts.inserted());
                    snapshot.setDeletedRows(counts.deleted());
                    snapshot.setChangedRows(counts.changed());
                }
                snapshot = snapshots.saveAndFlush(snapshot);
                // The catalog row: what lists show without reading any version.
                jdbc.update("UPDATE ver_export_snapshot_series SET template_name = ?, list_name = ?, "
                                + "version_count = COALESCE(version_count, 0) + 1, latest_snapshot_id = ?, latest_label = ?, latest_at = ?, "
                                + "exports_served = COALESCE(exports_served, 0) + 1, total_rows = COALESCE(total_rows, 0) + ?, "
                                + "stored_rows = COALESCE(stored_rows, 0) + ?, last_used_at = ? WHERE family_id = ? AND list_code = ?",
                        snapshot.getTemplateName(), snapshot.getListName(), snapshot.getId(), snapshot.getLabel(), snapshot.getCreatedAt(),
                        snapshot.getRowCount(), snapshot.getStoredRows(), snapshot.getCreatedAt(), familyId, listCode);
                // Last: the filled table takes the version's name.
                tables.rename(stage, snapshot.getTableName());
                return new Resolution(snapshot, true);
            });
        } catch (RuntimeException ex) {
            if (stage != null) {
                tables.drop(stage); // no version was made; the half-built table goes
            }
            throw ex;
        }
        DataSnapshot snapshot = Objects.requireNonNull(resolution).snapshot();
        if (stage != null && !resolution.created()) {
            tables.drop(stage); // the same data was stored by another export meanwhile
        }
        if (resolution.created()) {
            audit.snapshot(snapshot.getId(), "DATA_VERSION_CREATED", snapshot.getTemplateName() + " · " + snapshot.getListName()
                    + " data version " + snapshot.getLabel() + ": " + snapshot.getRowCount() + " rows in table " + snapshot.getTableName()
                    + (snapshot.getPreviousLabel() == null ? "" : ". Against " + snapshot.getPreviousLabel()
                    + ": +" + snapshot.getInsertedRows() + " −" + snapshot.getDeletedRows() + " ~" + snapshot.getChangedRows()));
        } else {
            audit.snapshot(snapshot.getId(), "DATA_VERSION_REUSED", snapshot.getTemplateName() + " · " + snapshot.getListName()
                    + " data unchanged: reused data version " + snapshot.getLabel() + " (" + snapshot.getTableName() + ")");
        }
        log.info("{} data version {} for {} / {} ({} rows, table {})", resolution.created() ? "Created" : "Reused",
                snapshot.getLabel(), snapshot.getTemplateName(), snapshot.getListCode(), snapshot.getRowCount(), snapshot.getTableName());
        return resolution;
    }

    /**
     * ver_list_apac_v1_1; when that name is taken (the same list name exported from another source list,
     * or two names that read the same once cleaned) the source list code is added, then a number.
     */
    private String freeTableName(String listName, int major, int minor, String listCode) {
        String name = VersionTableRepository.tableName(listName, major, minor);
        if (!taken(name)) {
            return name;
        }
        String withList = VersionTableRepository.tableName(listName + " " + listCode, major, minor);
        if (!taken(withList)) {
            return withList;
        }
        for (int n = 2; ; n++) {
            String numbered = VersionTableRepository.tableName(listName + " " + listCode + " " + n, major, minor);
            if (!taken(numbered)) {
                return numbered;
            }
        }
    }

    private boolean taken(String name) {
        return snapshots.existsByTableName(name) || tables.exists(name);
    }

    /** Creates the lock row for a template and list once; a concurrent creator simply wins. */
    private void ensureSeries(Long familyId, String listCode) {
        try {
            separateTransaction.executeWithoutResult(status -> {
                Integer existing = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ver_export_snapshot_series WHERE family_id = ? AND list_code = ?",
                        Integer.class, familyId, listCode);
                if (existing == null || existing == 0) {
                    jdbc.update("INSERT INTO ver_export_snapshot_series (family_id, list_code) VALUES (?, ?)", familyId, listCode);
                }
            });
        } catch (DataIntegrityViolationException ignored) {
            // another export created it first
        }
    }

    /** Copies the staged rows into the stage table, in batches, in file order. */
    private void fillStage(String stage, List<String> dbColumns, Path staging) {
        long line = 0;
        try (BufferedReader in = Files.newBufferedReader(staging, StandardCharsets.UTF_8)) {
            List<Object[]> batch = new ArrayList<>(BATCH);
            for (String text = in.readLine(); text != null; text = in.readLine()) {
                int tab = text.indexOf('\t');
                batch.add(tableRow(++line, text.substring(0, tab), fromJson(text.substring(tab + 1)), dbColumns.size()));
                if (batch.size() >= BATCH) {
                    tables.insert(stage, dbColumns, batch);
                    batch.clear();
                }
            }
            tables.insert(stage, dbColumns, batch);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the staged data version", ex);
        }
    }

    /** One row of a version table: line, record id, row key, the entry fields, then the list's values. */
    private static Object[] tableRow(long line, String rowKey, List<Object> values, int columnCount) {
        Object[] row = new Object[VersionTableRepository.ENTRY_COLUMNS.length + columnCount];
        row[0] = line;
        row[1] = number(values.get(0));
        row[2] = rowKey;
        row[3] = values.get(1);
        row[4] = values.get(2);
        row[5] = values.get(3);
        row[6] = date(values.get(4));
        row[7] = values.get(5);
        row[8] = values.get(6);
        row[9] = values.get(7);
        row[10] = values.get(8);
        row[11] = date(values.get(9));
        row[12] = Boolean.TRUE.equals(values.get(10));
        row[13] = Boolean.TRUE.equals(values.get(11));
        row[14] = Boolean.TRUE.equals(values.get(12));
        List<String> cells = strings(values.get(13) instanceof List<?> list ? new ArrayList<>(list) : List.of());
        for (int index = 0; index < columnCount; index++) {
            row[VersionTableRepository.ENTRY_COLUMNS.length + index] = index < cells.size() ? cells.get(index) : null;
        }
        return row;
    }

    /** A row in a fixed form: the list entry fields, then the template's values in column order. */
    private static List<Object> canonical(Map<String, Object> record, List<FormatField> fields) {
        List<Object> row = new ArrayList<>(14);
        row.add(number(record.get("id")));
        row.add(text(record.get(SourceCatalogService.RECORD_KEY)));
        row.add(text(record.get("name")));
        row.add(text(record.get("country")));
        row.add(text(record.get("record_date")));
        row.add(text(record.get("action")));
        row.add(text(record.get("risk_rating")));
        row.add(text(record.get("businessLine")));
        row.add(text(record.get("region")));
        row.add(text(record.get("changedOn")));
        row.add(Boolean.TRUE.equals(record.get("sanctions")));
        row.add(Boolean.TRUE.equals(record.get("worldcheck")));
        row.add(Boolean.TRUE.equals(record.get("pfa")));
        List<String> cells = new ArrayList<>(fields.size());
        for (FormatField field : fields) {
            cells.add(text(record.get(field.recordKey())));
        }
        row.add(cells);
        return row;
    }

    /** The column layout of a version: per list column its name in files, gs_ source and column in the version table. */
    private static List<Map<String, String>> columns(List<FormatField> fields) {
        List<String> dbColumns = VersionTableRepository.columnNames(fields.stream().map(FormatField::getHeaderName).toList());
        List<Map<String, String>> columns = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            FormatField field = fields.get(index);
            Map<String, String> column = new LinkedHashMap<>();
            column.put("header", field.getHeaderName());
            column.put("table", field.getSourceTable());
            column.put("column", field.getSourceColumn());
            column.put("dbColumn", dbColumns.get(index));
            columns.add(column);
        }
        return columns;
    }

    @Override
    public List<Map<String, String>> columns(DataSnapshot snapshot) {
        return parseColumns(snapshot.getColumnsJson(), snapshot.getLabel());
    }

    private static List<Map<String, String>> parseColumns(String json, String label) {
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, String>>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Data version " + label + " has an unreadable column layout", ex);
        }
    }

    /** The version table's columns for the list's columns, in order. */
    private static List<String> dbColumns(String columnsJson) {
        return parseColumns(columnsJson, "").stream().map(column -> column.get("dbColumn")).toList();
    }

    /** The list's values, as a SELECT list, in column order. */
    private static String cellSelect(DataSnapshot snapshot) {
        return dbColumns(snapshot.getColumnsJson()).stream().map(column -> ", " + VersionTableRepository.quote(column))
                .collect(java.util.stream.Collectors.joining());
    }

    private static String table(DataSnapshot snapshot) {
        if (snapshot.getTableName() == null) {
            throw new IllegalStateException("Data version " + snapshot.getLabel() + " has no table");
        }
        return VersionTableRepository.quote(snapshot.getTableName());
    }

    @Override
    public void stream(DataSnapshot snapshot, Selection selection, List<FormatField> fields, Consumer<Map<String, Object>> consumer) {
        StringBuilder sql = new StringBuilder("SELECT " + ENTRY_SELECT + cellSelect(snapshot) + " FROM " + table(snapshot) + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        String flag = switch (selection.scope() == null ? "ALL" : selection.scope().toUpperCase()) {
            case "SANCTIONS" -> "\"_on_sanctions\"";
            case "WORLDCHECK" -> "\"_on_worldcheck\"";
            case "PFA" -> "\"_on_pfa\"";
            default -> null;
        };
        if (flag != null) {
            sql.append(" AND ").append(flag).append(" = TRUE");
        }
        if ("DELTA".equalsIgnoreCase(selection.exportMode())) {
            sql.append(" AND \"_action\" IS NOT NULL");
            if (selection.fromDate() != null && selection.toDate() != null) {
                sql.append(" AND \"_changed_on\" >= ? AND \"_changed_on\" < ?");
                args.add(Date.valueOf(selection.fromDate()));
                args.add(Date.valueOf(selection.toDate().plusDays(1)));
            }
        }
        sql.append(" ORDER BY \"_line\"");
        JdbcStreams.query(dataSource, sql.toString(), args, rs -> consumer.accept(record(rs, fields)));
    }

    private static Map<String, Object> record(ResultSet rs, List<FormatField> fields) throws SQLException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", rs.getLong(1));
        record.put(SourceCatalogService.RECORD_KEY, rs.getString(2));
        record.put("name", rs.getString(3));
        record.put("country", rs.getString(4));
        record.put("record_date", isoDate(rs.getDate(5)));
        record.put("action", rs.getString(6));
        record.put("risk_rating", rs.getString(7));
        record.put("businessLine", rs.getString(8));
        record.put("region", rs.getString(9));
        record.put("changedOn", isoDate(rs.getDate(10)));
        record.put("sanctions", rs.getBoolean(11));
        record.put("worldcheck", rs.getBoolean(12));
        record.put("pfa", rs.getBoolean(13));
        int cellCount = rs.getMetaData().getColumnCount() - 13;
        for (int index = 0; index < fields.size(); index++) {
            record.put(fields.get(index).recordKey(), index < cellCount ? rs.getString(14 + index) : null);
        }
        return record;
    }

    @Override
    public List<DataVersionRowsResponse.Row> rows(DataSnapshot snapshot, int offset, int limit) {
        int cellCount = dbColumns(snapshot.getColumnsJson()).size();
        return jdbc.query("SELECT \"_line\", \"_record_id\"" + cellSelect(snapshot) + " FROM " + table(snapshot)
                        + " ORDER BY \"_line\" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, index) -> {
                    List<String> cells = new ArrayList<>(cellCount);
                    for (int column = 0; column < cellCount; column++) {
                        cells.add(rs.getString(3 + column));
                    }
                    return new DataVersionRowsResponse.Row(rs.getLong(1), rs.getLong(2), cells);
                },
                Math.max(0, offset), Math.min(500, Math.max(1, limit)));
    }

    @Override
    public DataVersionComparisonResponse.Counts counts(Long newer, Long older) {
        return counts(get(newer).getTableName(), get(older).getTableName());
    }

    /** The same, between two version tables (the newer one may still be a stage table). */
    private DataVersionComparisonResponse.Counts counts(String newerTable, String olderTable) {
        String n = VersionTableRepository.quote(newerTable);
        String o = VersionTableRepository.quote(olderTable);
        long inserted = count("SELECT COUNT(*) FROM " + n + " n WHERE NOT EXISTS "
                + "(SELECT 1 FROM " + o + " o WHERE o.\"_record_id\" = n.\"_record_id\")");
        long deleted = count("SELECT COUNT(*) FROM " + o + " o WHERE NOT EXISTS "
                + "(SELECT 1 FROM " + n + " n WHERE n.\"_record_id\" = o.\"_record_id\")");
        long changed = count("SELECT COUNT(*) FROM " + n + " n JOIN " + o + " o ON o.\"_record_id\" = n.\"_record_id\" "
                + "WHERE o.\"_row_key\" <> n.\"_row_key\"");
        long unchanged = count("SELECT COUNT(*) FROM " + n + " n JOIN " + o + " o ON o.\"_record_id\" = n.\"_record_id\" "
                + "WHERE o.\"_row_key\" = n.\"_row_key\"");
        return new DataVersionComparisonResponse.Counts(inserted, deleted, changed, unchanged);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    @Override
    public DataVersionComparisonResponse compare(Long newerId, Long olderId, int offset, int limit) {
        if (Objects.equals(newerId, olderId)) {
            throw new BadRequestException("Choose two different data versions to compare");
        }
        DataSnapshot newer = get(newerId);
        DataSnapshot older = get(olderId);
        if (!newer.getFamilyId().equals(older.getFamilyId()) || !newer.getListCode().equals(older.getListCode())) {
            throw new BadRequestException("Only data versions of the same list and source list can be compared");
        }
        String n = table(newer);
        String o = table(older);
        int size = Math.min(200, Math.max(1, limit));
        List<Long> page = jdbc.queryForList("SELECT rid FROM ("
                        + "SELECT n.\"_record_id\" AS rid FROM " + n + " n JOIN " + o + " o ON o.\"_record_id\" = n.\"_record_id\" "
                        + "WHERE o.\"_row_key\" <> n.\"_row_key\" "
                        + "UNION ALL SELECT n.\"_record_id\" FROM " + n + " n WHERE NOT EXISTS "
                        + "(SELECT 1 FROM " + o + " o WHERE o.\"_record_id\" = n.\"_record_id\") "
                        + "UNION ALL SELECT o.\"_record_id\" FROM " + o + " o WHERE NOT EXISTS "
                        + "(SELECT 1 FROM " + n + " n WHERE n.\"_record_id\" = o.\"_record_id\")"
                        + ") x ORDER BY rid OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                Long.class, Math.max(0, offset), size + 1);
        boolean more = page.size() > size;
        if (more) {
            page = page.subList(0, size);
        }
        Map<Long, StoredRow> before = load(older, page);
        Map<Long, StoredRow> after = load(newer, page);
        List<Map<String, String>> beforeColumns = columns(older);
        List<Map<String, String>> afterColumns = columns(newer);
        boolean sameLayout = beforeColumns.stream().map(column -> column.get("header")).toList()
                .equals(afterColumns.stream().map(column -> column.get("header")).toList());
        List<DataVersionComparisonResponse.Change> changes = new ArrayList<>();
        for (Long recordId : page) {
            StoredRow old = before.get(recordId);
            StoredRow now = after.get(recordId);
            String kind = old == null ? "ADDED" : now == null ? "REMOVED" : "CHANGED";
            String bnpId = now != null ? now.bnpId() : old != null ? old.bnpId() : null;
            changes.add(new DataVersionComparisonResponse.Change(recordId, bnpId, kind, cellChanges(old, now, beforeColumns, afterColumns)));
        }
        return new DataVersionComparisonResponse(newer.getId(), newer.getLabel(), older.getId(), older.getLabel(), sameLayout,
                counts(newer.getTableName(), older.getTableName()), Math.max(0, offset), changes, more);
    }

    private record StoredRow(String bnpId, Map<String, String> entry, List<String> cells) {
    }

    private static final String[][] ENTRY_FIELDS = {
            {"name", "Entry name"}, {"country", "Entry country"}, {"record_date", "Record date"}, {"action", "Action"},
            {"risk_rating", "Risk rating"}, {"business_line", "Business line"}, {"region", "Region"},
            {"changed_on", "Changed on"}, {"on_sanctions", "On Sanctions"}, {"on_worldcheck", "On World-Check"}, {"on_pfa", "On PFA"}
    };

    /** Rows of one version by record id. */
    private Map<Long, StoredRow> load(DataSnapshot snapshot, List<Long> recordIds) {
        if (recordIds.isEmpty()) {
            return Map.of();
        }
        int cellCount = dbColumns(snapshot.getColumnsJson()).size();
        Map<Long, StoredRow> rows = new HashMap<>();
        jdbc.query("SELECT " + ENTRY_SELECT + cellSelect(snapshot) + " FROM " + table(snapshot) + " WHERE \"_record_id\" IN ("
                + SqlUtils.marks(recordIds.size()) + ")", rs -> {
            Map<String, String> entry = new LinkedHashMap<>();
            for (int index = 0; index < ENTRY_FIELDS.length; index++) {
                Object value = rs.getObject(index + 3);
                entry.put(ENTRY_FIELDS[index][0], value == null ? null : value instanceof Date date ? date.toLocalDate().toString() : String.valueOf(value));
            }
            List<String> cells = new ArrayList<>(cellCount);
            for (int column = 0; column < cellCount; column++) {
                cells.add(rs.getString(14 + column));
            }
            rows.put(rs.getLong(1), new StoredRow(rs.getString(2), entry, cells));
        }, recordIds.toArray());
        return rows;
    }

    /** Values that differ: template columns matched by name in the file, then the list entry's own fields. */
    private static List<DataVersionComparisonResponse.CellChange> cellChanges(StoredRow before, StoredRow after,
                                                List<Map<String, String>> beforeColumns, List<Map<String, String>> afterColumns) {
        List<DataVersionComparisonResponse.CellChange> changes = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        afterColumns.forEach(column -> headers.add(column.get("header")));
        beforeColumns.forEach(column -> {
            if (!headers.contains(column.get("header"))) {
                headers.add(column.get("header"));
            }
        });
        for (String header : headers) {
            String old = value(before, beforeColumns, header);
            String now = value(after, afterColumns, header);
            if (!Objects.equals(old, now)) {
                changes.add(new DataVersionComparisonResponse.CellChange(header, old, now));
            }
        }
        if (before != null && after != null) {
            for (String[] field : ENTRY_FIELDS) {
                String old = before.entry().get(field[0]);
                String now = after.entry().get(field[0]);
                if (!Objects.equals(old, now)) {
                    changes.add(new DataVersionComparisonResponse.CellChange(field[1] + " (list entry)", old, now));
                }
            }
        }
        return changes;
    }

    private static String value(StoredRow row, List<Map<String, String>> columns, String header) {
        if (row == null) {
            return null;
        }
        for (int index = 0; index < columns.size(); index++) {
            if (header.equals(columns.get(index).get("header"))) {
                return index < row.cells().size() ? row.cells().get(index) : null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ files

    @Override
    public StorageService.PendingFile writeFile(DataSnapshot snapshot, ExportFormat format, String audience, Selection selection,
                                                java.util.function.Predicate<Map<String, Object>> keep) {
        List<FormatField> fields = templates.fields(snapshot.getTemplateId());
        StorageService.PendingFile pending = storage.reserve(
                snapshot.getTemplateName() + "_" + snapshot.getListCode() + "_data_" + snapshot.getLabel().replace('.', '-')
                        + (audience == null ? "" : "_" + audience), snapshot.getSourceVersionId(), format.fileExtension());
        try (ExportFormat.RecordWriter writer = format.open(pending.temp(), snapshot.getSourceVersionId(),
                audience == null ? "All entries" : audience, snapshot.getTemplateId())) {
            stream(snapshot, selection, fields, record -> {
                if (keep == null || keep.test(record)) {
                    try {
                        writer.write(record);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            });
            writer.finish();
        } catch (IOException ex) {
            storage.discard(pending);
            throw new UncheckedIOException("Could not write the file: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            storage.discard(pending);
            throw ex;
        }
        return pending;
    }

    // ------------------------------------------------------------------ helpers

    private Path stagingFile() {
        try {
            Path directory = storage.getRoot().resolve("staging");
            Files.createDirectories(directory);
            return directory.resolve(UUID.randomUUID() + ".staging");
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not create the staging directory", ex);
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<Object> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(value, new TypeReference<List<Object>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unreadable stored row", ex);
        }
    }

    private static List<String> strings(List<Object> values) {
        List<String> out = new ArrayList<>(values.size());
        values.forEach(value -> out.add(value == null ? null : String.valueOf(value)));
        return Collections.unmodifiableList(out);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value).trim());
    }

    private static Date date(Object value) {
        return value == null ? null : Date.valueOf(LocalDate.parse(String.valueOf(value)));
    }

    private static String isoDate(Date date) {
        return date == null ? null : date.toLocalDate().toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
