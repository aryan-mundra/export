package com.bnpp.export.repository;

import com.bnpp.export.model.DataVersion;
import com.bnpp.export.model.RecordChange;
import com.bnpp.export.util.SqlUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data versions of lists: one header row per version in ver_export_data_version, and one row per record in
 * ver_export_data_record (group, key, content hash and the nested record as JSON).
 *
 * <p>Comparison queries are portable (PostgreSQL and H2): records in both versions are joined on (group, key), and
 * records in only one of them are found with NOT EXISTS (H2 has no FULL OUTER JOIN).
 */
@Repository
public class DataVersionRepository {

    private static final String VERSIONS = "ver_export_data_version";
    private static final String RECORDS = "ver_export_data_record";
    private static final String COLUMNS = "id, family_id, template_id, template_version, list_name, version_number, record_count, "
            + "group_counts, created_by, created_at";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public DataVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ versions

    public Optional<DataVersion> find(Long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + VERSIONS + " WHERE id = ?", (rs, i) -> version(rs), id).stream().findFirst();
    }

    /** The versions of one list newest first, or of every list when familyId is null. */
    public List<DataVersion> findByFamily(Long familyId) {
        String sql = "SELECT " + COLUMNS + " FROM " + VERSIONS + (familyId == null ? "" : " WHERE family_id = ?")
                + " ORDER BY family_id, version_number DESC";
        return familyId == null ? jdbc.query(sql, (rs, i) -> version(rs)) : jdbc.query(sql, (rs, i) -> version(rs), familyId);
    }

    public Optional<DataVersion> findLatest(Long familyId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + VERSIONS + " WHERE family_id = ? ORDER BY version_number DESC FETCH FIRST 1 ROWS ONLY",
                (rs, i) -> version(rs), familyId).stream().findFirst();
    }

    public String fingerprint(Long id) {
        return jdbc.queryForObject("SELECT fingerprint FROM " + VERSIONS + " WHERE id = ?", String.class, id);
    }

    public String layout(Long id) {
        return jdbc.queryForObject("SELECT layout FROM " + VERSIONS + " WHERE id = ?", String.class, id);
    }

    public int nextNumber(Long familyId) {
        Integer last = jdbc.queryForObject("SELECT MAX(version_number) FROM " + VERSIONS + " WHERE family_id = ?", Integer.class, familyId);
        return last == null ? 1 : last + 1;
    }

    /** Stores a version header and returns its id. */
    public Long insert(Long familyId, Long templateId, int templateVersion, String listName, int number, String fingerprint,
                       long records, Map<String, Long> groups, String layout, String createdBy) {
        jdbc.update("INSERT INTO " + VERSIONS + " (family_id, template_id, template_version, list_name, version_number, fingerprint, "
                        + "record_count, group_counts, layout, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                familyId, templateId, templateVersion, listName, number, fingerprint, records, json(groups), layout, createdBy,
                Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject("SELECT id FROM " + VERSIONS + " WHERE family_id = ? AND version_number = ?", Long.class, familyId, number);
    }

    // ------------------------------------------------------------------ records

    /** Stores records: each row is {versionId, group, key, hash, doc}. */
    public void insertRecords(List<Object[]> rows) {
        jdbc.batchUpdate("INSERT INTO " + RECORDS + " (version_id, group_name, record_key, record_hash, doc) VALUES (?, ?, ?, ?, ?)", rows);
    }

    /** The next page of a group's records in key order, after {@code afterKey}: [key, doc]. */
    public List<String[]> page(Long versionId, String group, String afterKey, int limit) {
        return jdbc.query("SELECT record_key, doc FROM " + RECORDS + " WHERE version_id = ? AND group_name = ?"
                        + (afterKey == null ? "" : " AND record_key > ?") + " ORDER BY record_key FETCH FIRST " + limit + " ROWS ONLY",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)},
                afterKey == null ? new Object[] {versionId, group} : new Object[] {versionId, group, afterKey});
    }

    public Optional<String> doc(Long versionId, String group, String key) {
        return jdbc.query("SELECT doc FROM " + RECORDS + " WHERE version_id = ? AND group_name = ? AND record_key = ?",
                (rs, i) -> rs.getString(1), versionId, group, key).stream().findFirst();
    }

    // ------------------------------------------------------------------ comparing two versions

    /** Per group: {added, removed, changed, unchanged} from {@code before} to {@code after}. */
    public Map<String, long[]> countChanges(Long after, Long before) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        jdbc.query("SELECT a.group_name, SUM(CASE WHEN a.record_hash <> b.record_hash THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN a.record_hash = b.record_hash THEN 1 ELSE 0 END)"
                        + " FROM " + RECORDS + " a JOIN " + RECORDS + " b ON b.version_id = ? AND b.group_name = a.group_name"
                        + " AND b.record_key = a.record_key WHERE a.version_id = ? GROUP BY a.group_name",
                rs -> {
                    long[] c = counts.computeIfAbsent(rs.getString(1), ignored -> new long[4]);
                    c[2] = rs.getLong(2);
                    c[3] = rs.getLong(3);
                }, before, after);
        for (int side = 0; side < 2; side++) {
            int index = side;
            jdbc.query("SELECT a.group_name, COUNT(*) FROM " + RECORDS + " a WHERE a.version_id = ? AND NOT EXISTS (SELECT 1 FROM "
                            + RECORDS + " b WHERE b.version_id = ? AND b.group_name = a.group_name AND b.record_key = a.record_key)"
                            + " GROUP BY a.group_name",
                    rs -> {
                        counts.computeIfAbsent(rs.getString(1), ignored -> new long[4])[index] = rs.getLong(2);
                    }, side == 0 ? after : before, side == 0 ? before : after);
        }
        return counts;
    }

    /** The next page of a group's changed records (in both versions, different hashes): [key, after doc, before doc]. */
    public List<String[]> changedPage(Long after, Long before, String group, String afterKey, int limit) {
        return jdbc.query("SELECT a.record_key, a.doc, b.doc FROM " + RECORDS + " a JOIN " + RECORDS + " b"
                        + " ON b.version_id = ? AND b.group_name = a.group_name AND b.record_key = a.record_key"
                        + " WHERE a.version_id = ? AND a.group_name = ? AND a.record_hash <> b.record_hash"
                        + (afterKey == null ? "" : " AND a.record_key > ?") + " ORDER BY a.record_key FETCH FIRST " + limit + " ROWS ONLY",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
                afterKey == null ? new Object[] {before, after, group} : new Object[] {before, after, group, afterKey});
    }

    /** A record of either version, with both sides' hash and content (null on the side it is missing from). */
    public record Compared(String key, String afterHash, String beforeHash, String afterDoc, String beforeDoc) {

        public RecordChange change() {
            if (afterHash == null) {
                return RecordChange.REMOVED;
            }
            if (beforeHash == null) {
                return RecordChange.ADDED;
            }
            return afterHash.equals(beforeHash) ? RecordChange.UNCHANGED : RecordChange.CHANGED;
        }
    }

    /** One page of a group's records across both versions, filtered by change and key text: the total and the page. */
    public Map.Entry<Long, List<Compared>> compared(Long after, Long before, String group, RecordChange.Filter filter, String query,
                                                     int offset, int limit) {
        // Every key of either version once: the newer version's records (with the older one's when present), then
        // the older version's records missing from the newer one.
        String union = "SELECT a.record_key AS record_key, a.record_hash AS after_hash, b.record_hash AS before_hash,"
                + " a.doc AS after_doc, b.doc AS before_doc"
                + " FROM " + RECORDS + " a LEFT JOIN " + RECORDS + " b ON b.version_id = ? AND b.group_name = a.group_name"
                + " AND b.record_key = a.record_key WHERE a.version_id = ? AND a.group_name = ?"
                + " UNION ALL SELECT b.record_key, CAST(NULL AS VARCHAR(64)), b.record_hash, CAST(NULL AS TEXT), b.doc FROM " + RECORDS + " b"
                + " WHERE b.version_id = ? AND b.group_name = ? AND NOT EXISTS (SELECT 1 FROM " + RECORDS + " x"
                + " WHERE x.version_id = ? AND x.group_name = b.group_name AND x.record_key = b.record_key)";
        String from = " FROM (" + union + ") u WHERE " + switch (filter) {
            case ADDED -> "u.before_hash IS NULL";
            case REMOVED -> "u.after_hash IS NULL";
            case CHANGED -> "u.after_hash IS NOT NULL AND u.before_hash IS NOT NULL AND u.after_hash <> u.before_hash";
            case UNCHANGED -> "u.after_hash = u.before_hash";
            case ALL -> "1 = 1";
            case CHANGES -> "(u.after_hash IS NULL OR u.before_hash IS NULL OR u.after_hash <> u.before_hash)";
        };
        List<Object> binds = new ArrayList<>(List.of(before, after, group, before, group, after));
        if (query != null && !query.isBlank()) {
            from += " AND LOWER(u.record_key) LIKE ? ESCAPE '\\'";
            binds.add("%" + SqlUtils.likeEscape(query.trim().toLowerCase(java.util.Locale.ROOT)) + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, binds.toArray());
        List<Object> pageBinds = new ArrayList<>(binds);
        pageBinds.add(offset);
        pageBinds.add(limit);
        List<Compared> page = jdbc.query("SELECT u.record_key, u.after_hash, u.before_hash, u.after_doc, u.before_doc" + from
                        + " ORDER BY u.record_key OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, i) -> new Compared(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                pageBinds.toArray());
        return Map.entry(total == null ? 0L : total, page);
    }

    // ------------------------------------------------------------------ mapping

    private static DataVersion version(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new DataVersion(rs.getLong("id"), rs.getLong("family_id"), rs.getLong("template_id"), rs.getInt("template_version"),
                rs.getString("list_name"), rs.getInt("version_number"), rs.getLong("record_count"), counts(rs.getString("group_counts")),
                rs.getString("created_by"), created == null ? null : created.toLocalDateTime());
    }

    private static Map<String, Long> counts(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Long>>() { });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
