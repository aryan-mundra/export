package com.bnpp.export.service.impl;

import com.bnpp.export.service.RecordSelector;
import com.bnpp.export.service.SourceCatalogService;
import com.bnpp.export.util.JdbcStreams;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Reads the list entries of a certified version from VER_VERSION_MEMBER and hands them on in
 * batches. The same SQL runs on H2 (seeded with demo data) and on PostgreSQL.
 *
 * Full exports get every record of the version. Delta exports get records that carry an action,
 * limited to CHANGED_ON within the date window when one is given. The scope picks the list flag.
 * Business line and region are applied afterwards, per subscriber, by the orchestrator.
 */
@Component
public class RecordSelectorImpl implements RecordSelector {

    private static final int BATCH_SIZE = 500;

    private static final String SELECT = """
            SELECT RECORD_ID, BNP_ID, NAME, COUNTRY, RECORD_DATE, ACTION, RISK_RATING,
                   BUSINESS_LINE, REGION, CHANGED_ON, ON_SANCTIONS, ON_WORLDCHECK, ON_PFA
              FROM VER_VERSION_MEMBER
             WHERE VERSION_ID = ?""";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public RecordSelectorImpl(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setFetchSize(BATCH_SIZE);
    }

    @Override
    public void streamRecords(Long versionId,
                              String scope,
                              String exportMode,
                              LocalDate fromDate,
                              LocalDate toDate,
                              Consumer<List<Map<String, Object>>> consumer) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>();
        args.add(versionId);
        appendFilters(sql, args, scope, exportMode, fromDate, toDate);
        sql.append(" ORDER BY RECORD_ID");

        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
        JdbcStreams.query(dataSource, sql.toString(), args, rs -> {
            batch.add(toRecord(rs));
            if (batch.size() >= BATCH_SIZE) {
                consumer.accept(List.copyOf(batch));
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            consumer.accept(List.copyOf(batch));
        }
    }

    /**
     * One COUNT query instead of reading the records. Applies the same rules as an export: scope,
     * delta window, the subscriber's business line and region (case-insensitive, as the export does),
     * the template's row filters, and a record is quarantined when any required field is empty or only spaces.
     */
    @Override
    public Optional<Counts> count(CountRequest request) {
        List<String> blankChecks = new ArrayList<>();
        for (String field : request.requiredFields()) {
            Column column = COLUMNS.get(field);
            if (column == null) {
                return Optional.empty();
            }
            blankChecks.add(column.text()
                    ? "(" + column.name() + " IS NULL OR TRIM(" + column.name() + ") IS NULL OR LENGTH(TRIM(" + column.name() + ")) = 0)"
                    : column.name() + " IS NULL");
        }
        String rejected = blankChecks.isEmpty() ? "0" : "CASE WHEN " + String.join(" OR ", blankChecks) + " THEN 1 ELSE 0 END";
        StringBuilder sql = new StringBuilder("SELECT COUNT(*), COALESCE(SUM(" + rejected + "), 0) FROM VER_VERSION_MEMBER e WHERE VERSION_ID = ?");
        List<Object> args = new ArrayList<>();
        args.add(request.versionId());
        appendFilters(sql, args, request.scope(), request.exportMode(), request.fromDate(), request.toDate());
        if (request.businessLine() != null && !request.businessLine().isBlank()) {
            sql.append(" AND UPPER(BUSINESS_LINE) = UPPER(?)");
            args.add(request.businessLine());
        }
        if (request.region() != null && !request.region().isBlank()) {
            sql.append(" AND UPPER(REGION) = UPPER(?)");
            args.add(request.region());
        }
        if (request.rowFilter() != null && !request.rowFilter().isEmpty()) {
            sql.append(request.rowFilter().and());
            args.addAll(request.rowFilter().args());
        }
        return Optional.ofNullable(jdbcTemplate.queryForObject(sql.toString(), (rs, row) -> {
            long total = rs.getLong(1);
            long bad = rs.getLong(2);
            return new Counts(total - bad, bad);
        }, args.toArray()));
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String scope, String exportMode,
                               LocalDate fromDate, LocalDate toDate) {
        String flag = scopeColumn(scope);
        if (flag != null) {
            sql.append(" AND ").append(flag).append(" = 1");
        }
        if ("DELTA".equalsIgnoreCase(exportMode)) {
            sql.append(" AND ACTION IS NOT NULL");
            if (fromDate != null && toDate != null) {
                sql.append(" AND CHANGED_ON >= ? AND CHANGED_ON < ?");
                args.add(Date.valueOf(fromDate));
                args.add(Date.valueOf(toDate.plusDays(1)));
            }
        }
    }

    /** Record fields that can be checked in SQL, and whether the column holds text. */
    private static final Map<String, Column> COLUMNS = Map.of(
            "id", new Column("RECORD_ID", false),
            "name", new Column("NAME", true),
            "country", new Column("COUNTRY", true),
            "record_date", new Column("RECORD_DATE", false),
            "action", new Column("ACTION", true),
            "risk_rating", new Column("RISK_RATING", true));

    private record Column(String name, boolean text) {
    }

    private String scopeColumn(String scope) {
        if (scope == null) {
            return null;
        }
        return switch (scope.toUpperCase(Locale.ROOT)) {
            case "SANCTIONS" -> "ON_SANCTIONS";
            case "WORLDCHECK" -> "ON_WORLDCHECK";
            case "PFA" -> "ON_PFA";
            default -> null;
        };
    }

    /** Same keys the mock reader produces, so quality checks, templates and filters work unchanged. */
    private Map<String, Object> toRecord(ResultSet rs) throws SQLException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", rs.getLong("RECORD_ID"));
        record.put(SourceCatalogService.RECORD_KEY, rs.getString("BNP_ID"));
        record.put("name", rs.getString("NAME"));
        record.put("country", rs.getString("COUNTRY"));
        record.put("record_date", isoDate(rs.getDate("RECORD_DATE")));
        record.put("action", rs.getString("ACTION"));
        record.put("risk_rating", rs.getString("RISK_RATING"));
        record.put("businessLine", rs.getString("BUSINESS_LINE"));
        record.put("region", rs.getString("REGION"));
        record.put("changedOn", isoDate(rs.getDate("CHANGED_ON")));
        record.put("sanctions", rs.getInt("ON_SANCTIONS") == 1);
        record.put("worldcheck", rs.getInt("ON_WORLDCHECK") == 1);
        record.put("pfa", rs.getInt("ON_PFA") == 1);
        return record;
    }

    private String isoDate(Date date) {
        return date == null ? null : date.toLocalDate().toString();
    }
}
