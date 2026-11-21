package com.bnpp.export.service;

import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.format.RecordBatchProvider;
import com.bnpp.export.model.CertifiedVersion;
import com.bnpp.export.model.DataSnapshot;
import com.bnpp.export.model.FormatField;
import com.bnpp.export.model.FormatTemplate;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.repository.DataSnapshotRepository;
import com.bnpp.export.security.CurrentUser;
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
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Data versions (snapshots) of what a template produces from a list.
 *
 * <p>Building: the list entries of the certified version are read once, passed through the template's
 * row filters and given their gs_ values, exactly as an export does. Each row is written in a fixed
 * form (every value, empty ones included) and hashed; the dataset fingerprint is the hash of the
 * column layout and all row hashes in record order. Rows are staged in a file, so nothing large is
 * held in memory and nothing is written to the database until we know the data is new.
 *
 * <p>Numbering: under a lock on the template and list, an existing version with the same template
 * version and fingerprint is reused; otherwise the next number is taken. A unique key on
 * (template, list, fingerprint) backs this up, so two exports at the same moment cannot create twin
 * versions or the same number twice.
 *
 * <p>Storage: one header row per version; a line table (version, line, row hash); and each distinct
 * row stored once. A version that differs from the last one in five rows adds five rows.
 *
 * <p>Reading: files are built from the stored rows, so a historical version never needs the source
 * query again, and the same rows can be written as CSV or XML.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 500;

    /** Entry fields kept with each row, in the order they are written and hashed. */
    private static final String ROW_COLUMNS = "d.record_id, d.bnp_id, d.name, d.country, d.record_date, d.action, d.risk_rating, "
            + "d.business_line, d.region, d.changed_on, d.on_sanctions, d.on_worldcheck, d.on_pfa, d.cells";

    private final DataSnapshotRepository snapshots;
    private final CertifiedVersionRepository versions;
    private final FormatTemplateService templates;
    private final RowFilterService rowFilters;
    private final GoldenSourceEnricher enricher;
    private final RecordBatchProvider records;
    private final StorageService storage;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final TransactionTemplate separateTransaction;

    public SnapshotService(DataSnapshotRepository snapshots,
                           CertifiedVersionRepository versions,
                           FormatTemplateService templates,
                           RowFilterService rowFilters,
                           GoldenSourceEnricher enricher,
                           RecordBatchProvider records,
                           StorageService storage,
                           AuditService audit,
                           JdbcTemplate jdbc,
                           DataSource dataSource,
                           PlatformTransactionManager transactionManager) {
        this.snapshots = snapshots;
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

    /** The data version an export used, and whether the export created it or found the same data already stored. */
    public record Resolution(DataSnapshot snapshot, boolean created) {
    }

    public DataSnapshot get(Long id) {
        return snapshots.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Data version not found"));
    }

    // ------------------------------------------------------------------ building

    /**
     * Reads what the template produces from the certified version now, and returns the matching data
     * version: an existing one when the data is identical, otherwise a new one.
     */
    public Resolution resolve(Long certifiedVersionId, FormatTemplate template, Long jobId) {
        CertifiedVersion version = versions.findByVersionId(certifiedVersionId)
                .orElseThrow(() -> new IllegalStateException("Certified version " + certifiedVersionId + " was not found"));
        List<FormatField> fields = templates.fields(template.getId());
        if (fields.isEmpty()) {
            throw new IllegalStateException("Template " + template.getName() + " v" + template.getVersionNumber() + " has no columns");
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

    /** Numbers and stores a dataset under a lock on its template and list, or finds it already stored. */
    private Resolution register(FormatTemplate template, CertifiedVersion version, String columnsJson, String fingerprint,
                                long rowCount, Path staging, Long jobId) {
        Long familyId = template.getFamilyId();
        String listCode = version.getListCode();
        ensureSeries(familyId, listCode);
        Resolution resolution = transaction.execute(status -> {
            // Serialises numbering for this template and list; other templates and lists are not blocked.
            jdbc.queryForList("SELECT id FROM ver_export_snapshot_series WHERE family_id = ? AND list_code = ? FOR UPDATE",
                    familyId, listCode);
            DataSnapshot same = snapshots.findFirstByFamilyIdAndListCodeAndTemplateIdAndFingerprint(
                    familyId, listCode, template.getId(), fingerprint).orElse(null);
            if (same != null) {
                same.setReuseCount(same.getReuseCount() + 1);
                same.setLastUsedAt(LocalDateTime.now());
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
            snapshot.setFormatType(template.getFormatType());
            snapshot.setMajor(template.getVersionNumber());
            snapshot.setMinor(minor == null ? 0 : minor + 1);
            snapshot.setLabel(snapshot.getMajor() + "." + snapshot.getMinor());
            snapshot.setSourceVersionId(version.getVersionId());
            snapshot.setFingerprint(fingerprint);
            snapshot.setRowCount(rowCount);
            snapshot.setColumnsJson(columnsJson);
            snapshot.setCreatedBy(CurrentUser.actor());
            snapshot.setCreatedByName(CurrentUser.displayName());
            snapshot.setCreatedJobId(jobId);
            snapshot = snapshots.saveAndFlush(snapshot);
            snapshot.setStoredRows(storeRows(snapshot.getId(), staging));
            if (previous != null) {
                Counts counts = counts(snapshot.getId(), previous.getId());
                snapshot.setPreviousSnapshotId(previous.getId());
                snapshot.setPreviousLabel(previous.getLabel());
                snapshot.setInsertedRows(counts.inserted());
                snapshot.setDeletedRows(counts.deleted());
                snapshot.setChangedRows(counts.changed());
            }
            return new Resolution(snapshots.save(snapshot), true);
        });
        DataSnapshot snapshot = Objects.requireNonNull(resolution).snapshot();
        if (resolution.created()) {
            audit.snapshot(snapshot.getId(), "DATA_VERSION_CREATED", snapshot.getTemplateName() + " · " + snapshot.getListName()
                    + " data version " + snapshot.getLabel() + ": " + snapshot.getRowCount() + " rows, " + snapshot.getStoredRows()
                    + " new to storage" + (snapshot.getPreviousLabel() == null ? "" : ". Against " + snapshot.getPreviousLabel()
                    + ": +" + snapshot.getInsertedRows() + " −" + snapshot.getDeletedRows() + " ~" + snapshot.getChangedRows()));
        } else {
            audit.snapshot(snapshot.getId(), "DATA_VERSION_REUSED", snapshot.getTemplateName() + " · " + snapshot.getListName()
                    + " data unchanged: reused data version " + snapshot.getLabel());
        }
        log.info("{} data version {} for {} / {} ({} rows)", resolution.created() ? "Created" : "Reused",
                snapshot.getLabel(), snapshot.getTemplateName(), snapshot.getListCode(), snapshot.getRowCount());
        return resolution;
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

    /** Copies the staged rows into storage: lines always, row content only when not stored already. */
    private long storeRows(Long snapshotId, Path staging) {
        long stored = 0;
        long line = 0;
        try (BufferedReader in = Files.newBufferedReader(staging, StandardCharsets.UTF_8)) {
            List<String[]> batch = new ArrayList<>(BATCH);
            for (String text = in.readLine(); text != null; text = in.readLine()) {
                int tab = text.indexOf('\t');
                batch.add(new String[] {text.substring(0, tab), text.substring(tab + 1)});
                if (batch.size() >= BATCH) {
                    stored += storeBatch(snapshotId, batch, line);
                    line += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                stored += storeBatch(snapshotId, batch, line);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the staged data version", ex);
        }
        return stored;
    }

    private long storeBatch(Long snapshotId, List<String[]> batch, long firstLine) {
        List<String> keys = batch.stream().map(row -> row[0]).toList();
        Set<String> known = new HashSet<>(jdbc.queryForList(
                "SELECT row_key FROM ver_export_snapshot_data WHERE row_key IN (" + RowFilterService.marks(keys.size()) + ")",
                String.class, keys.toArray()));
        List<Object[]> data = new ArrayList<>();
        List<Object[]> lines = new ArrayList<>(batch.size());
        long line = firstLine;
        for (String[] row : batch) {
            List<Object> values = fromJson(row[1]);
            Long recordId = number(values.get(0));
            if (known.add(row[0])) {
                data.add(new Object[] {
                        row[0], recordId, values.get(1), values.get(2), values.get(3), date(values.get(4)), values.get(5),
                        values.get(6), values.get(7), values.get(8), date(values.get(9)), Boolean.TRUE.equals(values.get(10)),
                        Boolean.TRUE.equals(values.get(11)), Boolean.TRUE.equals(values.get(12)), toJson(values.get(13))});
            }
            lines.add(new Object[] {snapshotId, ++line, recordId, row[0]});
        }
        if (!data.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO ver_export_snapshot_data (row_key, record_id, bnp_id, name, country, record_date, action, "
                    + "risk_rating, business_line, region, changed_on, on_sanctions, on_worldcheck, on_pfa, cells) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", data);
        }
        jdbc.batchUpdate("INSERT INTO ver_export_snapshot_row (snapshot_id, line_no, record_id, row_key) VALUES (?, ?, ?, ?)", lines);
        return data.size();
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

    private static List<Map<String, String>> columns(List<FormatField> fields) {
        List<Map<String, String>> columns = new ArrayList<>();
        for (FormatField field : fields) {
            Map<String, String> column = new LinkedHashMap<>();
            column.put("header", field.getHeaderName());
            column.put("table", field.getSourceTable());
            column.put("column", field.getSourceColumn());
            columns.add(column);
        }
        return columns;
    }

    public List<Map<String, String>> columns(DataSnapshot snapshot) {
        try {
            return JSON.readValue(snapshot.getColumnsJson(), new TypeReference<List<Map<String, String>>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Data version " + snapshot.getLabel() + " has an unreadable column layout", ex);
        }
    }

    // ------------------------------------------------------------------ reading

    /** Which rows of a version to read: all of them, or what one subscriber receives. */
    public record Selection(String scope, String exportMode, LocalDate fromDate, LocalDate toDate) {
        public static final Selection ALL = new Selection("ALL", "FULL", null, null);
    }

    /**
     * Streams the stored rows of a version in line order as export records: the same keys the source
     * reader and the gs_ enricher produce, so quality checks, profile filters and file writers work
     * unchanged. The source tables are not read.
     */
    public void stream(DataSnapshot snapshot, Selection selection, List<FormatField> fields, Consumer<Map<String, Object>> consumer) {
        StringBuilder sql = new StringBuilder("SELECT " + ROW_COLUMNS + " FROM ver_export_snapshot_row r "
                + "JOIN ver_export_snapshot_data d ON d.row_key = r.row_key WHERE r.snapshot_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(snapshot.getId());
        String flag = switch (selection.scope() == null ? "ALL" : selection.scope().toUpperCase()) {
            case "SANCTIONS" -> "d.on_sanctions";
            case "WORLDCHECK" -> "d.on_worldcheck";
            case "PFA" -> "d.on_pfa";
            default -> null;
        };
        if (flag != null) {
            sql.append(" AND ").append(flag).append(" = TRUE");
        }
        if ("DELTA".equalsIgnoreCase(selection.exportMode())) {
            sql.append(" AND d.action IS NOT NULL");
            if (selection.fromDate() != null && selection.toDate() != null) {
                sql.append(" AND d.changed_on >= ? AND d.changed_on < ?");
                args.add(Date.valueOf(selection.fromDate()));
                args.add(Date.valueOf(selection.toDate().plusDays(1)));
            }
        }
        sql.append(" ORDER BY r.line_no");
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
        List<Object> cells = fromJson(rs.getString(14));
        for (int index = 0; index < fields.size(); index++) {
            record.put(fields.get(index).recordKey(), index < cells.size() ? cells.get(index) : null);
        }
        return record;
    }

    public record PageRow(long line, long recordId, List<String> cells) {
    }

    /** A page of a version's rows, in file order. */
    public List<PageRow> rows(DataSnapshot snapshot, int offset, int limit) {
        return jdbc.query("SELECT r.line_no, r.record_id, d.cells FROM ver_export_snapshot_row r "
                        + "JOIN ver_export_snapshot_data d ON d.row_key = r.row_key WHERE r.snapshot_id = ? "
                        + "ORDER BY r.line_no OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, index) -> new PageRow(rs.getLong(1), rs.getLong(2), strings(fromJson(rs.getString(3)))),
                snapshot.getId(), Math.max(0, offset), Math.min(500, Math.max(1, limit)));
    }

    // ------------------------------------------------------------------ comparing

    public record Counts(long inserted, long deleted, long changed, long unchanged) {
    }

    /** How {@code newer} differs from {@code older}, record by record (matched on record id). */
    public Counts counts(Long newer, Long older) {
        long inserted = count("SELECT COUNT(*) FROM ver_export_snapshot_row n WHERE n.snapshot_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM ver_export_snapshot_row o WHERE o.snapshot_id = ? AND o.record_id = n.record_id)", newer, older);
        long deleted = count("SELECT COUNT(*) FROM ver_export_snapshot_row o WHERE o.snapshot_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM ver_export_snapshot_row n WHERE n.snapshot_id = ? AND n.record_id = o.record_id)", older, newer);
        long changed = count("SELECT COUNT(*) FROM ver_export_snapshot_row n JOIN ver_export_snapshot_row o "
                + "ON o.snapshot_id = ? AND o.record_id = n.record_id WHERE n.snapshot_id = ? AND o.row_key <> n.row_key", older, newer);
        long unchanged = count("SELECT COUNT(*) FROM ver_export_snapshot_row n JOIN ver_export_snapshot_row o "
                + "ON o.snapshot_id = ? AND o.record_id = n.record_id WHERE n.snapshot_id = ? AND o.row_key = n.row_key", older, newer);
        return new Counts(inserted, deleted, changed, unchanged);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /** One value that differs between the two versions. */
    public record CellChange(String column, String before, String after) {
    }

    /** kind: ADDED (only in the newer version), REMOVED (only in the older one) or CHANGED. */
    public record Change(long recordId, String bnpId, String kind, List<CellChange> cells) {
    }

    public record Comparison(Long newerId, String newerLabel, Long olderId, String olderLabel, boolean sameLayout,
                             Counts counts, int offset, List<Change> changes, boolean more) {
    }

    public Comparison compare(Long newerId, Long olderId, int offset, int limit) {
        if (Objects.equals(newerId, olderId)) {
            throw new ResponseStatusException(BAD_REQUEST, "Choose two different data versions to compare");
        }
        DataSnapshot newer = get(newerId);
        DataSnapshot older = get(olderId);
        if (!newer.getFamilyId().equals(older.getFamilyId()) || !newer.getListCode().equals(older.getListCode())) {
            throw new ResponseStatusException(BAD_REQUEST, "Only data versions of the same template and list can be compared");
        }
        int size = Math.min(200, Math.max(1, limit));
        List<Object[]> page = jdbc.query("SELECT * FROM ("
                        + "SELECT n.record_id AS rid, o.row_key AS before_key, n.row_key AS after_key FROM ver_export_snapshot_row n "
                        + "JOIN ver_export_snapshot_row o ON o.snapshot_id = ? AND o.record_id = n.record_id "
                        + "WHERE n.snapshot_id = ? AND o.row_key <> n.row_key "
                        + "UNION ALL SELECT n.record_id, NULL, n.row_key FROM ver_export_snapshot_row n WHERE n.snapshot_id = ? AND NOT EXISTS "
                        + "(SELECT 1 FROM ver_export_snapshot_row o WHERE o.snapshot_id = ? AND o.record_id = n.record_id) "
                        + "UNION ALL SELECT o.record_id, o.row_key, NULL FROM ver_export_snapshot_row o WHERE o.snapshot_id = ? AND NOT EXISTS "
                        + "(SELECT 1 FROM ver_export_snapshot_row n WHERE n.snapshot_id = ? AND n.record_id = o.record_id)"
                        + ") x ORDER BY rid OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, index) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)},
                olderId, newerId, newerId, olderId, olderId, newerId, Math.max(0, offset), size + 1);
        boolean more = page.size() > size;
        if (more) {
            page = page.subList(0, size);
        }
        Set<String> keys = new HashSet<>();
        page.forEach(row -> {
            if (row[1] != null) keys.add((String) row[1]);
            if (row[2] != null) keys.add((String) row[2]);
        });
        Map<String, StoredRow> stored = load(keys);
        List<Map<String, String>> beforeColumns = columns(older);
        List<Map<String, String>> afterColumns = columns(newer);
        boolean sameLayout = beforeColumns.equals(afterColumns);
        List<Change> changes = new ArrayList<>();
        for (Object[] row : page) {
            StoredRow before = row[1] == null ? null : stored.get((String) row[1]);
            StoredRow after = row[2] == null ? null : stored.get((String) row[2]);
            String kind = before == null ? "ADDED" : after == null ? "REMOVED" : "CHANGED";
            String bnpId = after != null ? after.bnpId() : before != null ? before.bnpId() : null;
            changes.add(new Change((Long) row[0], bnpId, kind, cellChanges(before, after, beforeColumns, afterColumns)));
        }
        return new Comparison(newer.getId(), newer.getLabel(), older.getId(), older.getLabel(), sameLayout,
                counts(newerId, olderId), Math.max(0, offset), changes, more);
    }

    private record StoredRow(String bnpId, Map<String, String> entry, List<String> cells) {
    }

    private static final String[][] ENTRY_FIELDS = {
            {"name", "Entry name"}, {"country", "Entry country"}, {"record_date", "Record date"}, {"action", "Action"},
            {"risk_rating", "Risk rating"}, {"business_line", "Business line"}, {"region", "Region"},
            {"changed_on", "Changed on"}, {"on_sanctions", "On Sanctions"}, {"on_worldcheck", "On World-Check"}, {"on_pfa", "On PFA"}
    };

    private Map<String, StoredRow> load(Set<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<String> list = new ArrayList<>(keys);
        Map<String, StoredRow> rows = new HashMap<>();
        jdbc.query("SELECT d.row_key, " + ROW_COLUMNS + " FROM ver_export_snapshot_data d WHERE d.row_key IN ("
                + RowFilterService.marks(list.size()) + ")", rs -> {
            Map<String, String> entry = new LinkedHashMap<>();
            for (int index = 0; index < ENTRY_FIELDS.length; index++) {
                Object value = rs.getObject(index + 4);
                entry.put(ENTRY_FIELDS[index][0], value == null ? null : value instanceof Date date ? date.toLocalDate().toString() : String.valueOf(value));
            }
            rows.put(rs.getString(1), new StoredRow(rs.getString(3), entry, strings(fromJson(rs.getString(15)))));
        }, list.toArray());
        return rows;
    }

    /** Values that differ: template columns matched by name in the file, then the list entry's own fields. */
    private static List<CellChange> cellChanges(StoredRow before, StoredRow after,
                                                List<Map<String, String>> beforeColumns, List<Map<String, String>> afterColumns) {
        List<CellChange> changes = new ArrayList<>();
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
                changes.add(new CellChange(header, old, now));
            }
        }
        if (before != null && after != null) {
            for (String[] field : ENTRY_FIELDS) {
                String old = before.entry().get(field[0]);
                String now = after.entry().get(field[0]);
                if (!Objects.equals(old, now)) {
                    changes.add(new CellChange(field[1] + " (list entry)", old, now));
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

    /**
     * Writes a version as a file in any format, from the stored rows. With a subscriber, only the rows
     * it receives (scope, business line and region) and that pass the quality check, as an export does.
     *
     * @param keep decides per record whether it goes in the file (null keeps all)
     */
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
