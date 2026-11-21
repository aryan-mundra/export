package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatFilterRequest;
import com.bnpp.export.model.CertifiedVersion;
import com.bnpp.export.model.FormatField;
import com.bnpp.export.model.FormatFilter;
import com.bnpp.export.repository.CertifiedVersionRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * The data behind a template that is being built: one row per list entry of a certified version,
 * with the template's gs_ columns, exactly as the file would hold them, narrowed by the template's
 * row filters. Paged, sorted and counted in the database, so the size of the version does not matter.
 * Nothing is stored.
 */
@Service
public class DataExplorerService {

    public static final int MAX_PAGE = 500;
    public static final int FACET_VALUES = 200;

    private final FormatTemplateService formatTemplateService;
    private final RowFilterService rowFilters;
    private final GoldenSourceEnricher enricher;
    private final SourceCatalogService catalog;
    private final CertifiedVersionRepository versions;
    private final JdbcTemplate jdbc;

    public DataExplorerService(FormatTemplateService formatTemplateService,
                               RowFilterService rowFilters,
                               GoldenSourceEnricher enricher,
                               SourceCatalogService catalog,
                               CertifiedVersionRepository versions,
                               DataSource dataSource) {
        this.formatTemplateService = formatTemplateService;
        this.rowFilters = rowFilters;
        this.enricher = enricher;
        this.catalog = catalog;
        this.versions = versions;
        this.jdbc = new JdbcTemplate(dataSource);
        // A user is waiting: give up rather than keep a heavy query running on a shared database.
        this.jdbc.setQueryTimeout(60);
    }

    /**
     * @param sortColumn    index of the template column to sort on, or null for list order (the file's order)
     * @param sortDirection asc or desc
     * @param withTotal     false when only the next page is needed (the counts are already known)
     */
    public record ExploreRequest(Long versionId,
                                 List<FormatFieldRequest> fields,
                                 List<FormatFilterRequest> filters,
                                 Integer sortColumn,
                                 String sortDirection,
                                 Integer offset,
                                 Integer limit,
                                 Boolean withTotal) {
    }

    public record Column(String header, String table, String column, String type, String kind) {
    }

    public record Row(Long id, List<String> cells) {
    }

    /**
     * @param total   list entries that pass the filters (null when not asked for)
     * @param entries list entries in the version, before the filters (null when not asked for)
     */
    public record Page(Long versionId, String listName, List<Column> columns, Long total, Long entries,
                       int offset, List<Row> rows, boolean more) {
    }

    public record FacetRequest(Long versionId,
                               List<FormatFieldRequest> fields,
                               List<FormatFilterRequest> filters,
                               Integer column,
                               String search) {
    }

    public record Value(String value, long count) {
    }

    /**
     * What a column holds among the entries that pass the other filters: its distinct values with
     * the number of entries (text), or its smallest and largest value (numbers and dates), and how
     * many entries have it empty.
     *
     * @param more true when there are more values than shown; a search narrows them
     */
    public record Facet(String kind, List<Value> values, boolean more, long blank, String min, String max) {
    }

    public Page explore(ExploreRequest request) {
        CertifiedVersion version = version(request.versionId());
        List<FormatField> fields = fields(request.fields());
        List<FormatFilter> filters = rowFilters.validate(request.filters(), fields);
        List<SourceCatalogService.SourceTable> tables = catalog.tables();
        List<Column> columns = columns(tables, fields);
        int offset = Math.max(0, request.offset() == null ? 0 : request.offset());
        int limit = Math.min(MAX_PAGE, Math.max(1, request.limit() == null ? 100 : request.limit()));

        RowFilterService.Condition condition = rowFilters.condition(filters, "e");
        String from = " FROM " + RowFilterService.ENTRY_TABLE + " e WHERE e.VERSION_ID = ?" + condition.and();
        List<Object> args = new ArrayList<>();
        args.add(version.getVersionId());
        args.addAll(condition.args());

        String order = " ORDER BY e.RECORD_ID";
        Integer sort = request.sortColumn();
        if (sort != null && sort >= 0 && sort < fields.size() && !"other".equals(columns.get(sort).kind())) {
            FormatField field = fields.get(sort);
            SourceCatalogService.SourceTable table = RowFilterService.table(tables, field.getSourceTable());
            String column = SourceCatalogService.quote(table.column(field.getSourceColumn()).orElseThrow().name());
            String direction = "desc".equalsIgnoreCase(request.sortDirection()) ? "DESC" : "ASC";
            order = " ORDER BY (SELECT MIN(g." + column + ") FROM " + table.qualifiedName() + " g WHERE g."
                    + SourceCatalogService.quote(table.keyColumn()) + " = e.BNP_ID) " + direction + " NULLS LAST, e.RECORD_ID";
        }

        try {
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(offset);
            pageArgs.add(limit + 1);
            List<Map<String, Object>> records = jdbc.query(
                    "SELECT e.RECORD_ID, e.BNP_ID" + from + order + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                    (rs, index) -> {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("id", rs.getLong(1));
                        record.put(SourceCatalogService.RECORD_KEY, rs.getString(2));
                        return record;
                    }, pageArgs.toArray());
            boolean more = records.size() > limit;
            if (more) {
                records = records.subList(0, limit);
            }
            List<Row> rows = new ArrayList<>();
            for (Map<String, Object> record : enricher.enrich(records, fields)) {
                List<String> cells = new ArrayList<>(fields.size());
                for (FormatField field : fields) {
                    Object value = record.get(field.recordKey());
                    cells.add(value == null ? null : String.valueOf(value));
                }
                rows.add(new Row(((Number) record.get("id")).longValue(), cells));
            }
            Long total = null;
            Long entries = null;
            if (!Boolean.FALSE.equals(request.withTotal())) {
                total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
                entries = condition.isEmpty() ? total : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + RowFilterService.ENTRY_TABLE + " e WHERE e.VERSION_ID = ?",
                        Long.class, version.getVersionId());
            }
            return new Page(version.getVersionId(), version.getListName(), columns, total, entries, offset, rows, more);
        } catch (DataAccessException ex) {
            throw unavailable(ex);
        }
    }

    public Facet facet(FacetRequest request) {
        CertifiedVersion version = version(request.versionId());
        List<FormatField> fields = fields(request.fields());
        List<FormatFilter> filters = rowFilters.validate(request.filters(), fields);
        Integer index = request.column();
        if (index == null || index < 0 || index >= fields.size()) {
            throw new ResponseStatusException(BAD_REQUEST, "Choose a column of the template");
        }
        FormatField field = fields.get(index);
        List<SourceCatalogService.SourceTable> tables = catalog.tables();
        SourceCatalogService.SourceTable table = RowFilterService.table(tables, field.getSourceTable());
        SourceCatalogService.SourceColumn source = table.column(field.getSourceColumn()).orElseThrow();
        String kind = source.kind();
        if ("other".equals(kind)) {
            return new Facet(kind, List.of(), false, 0, null, null);
        }
        // Counts reflect every other filter, so the user sees what is left to choose from.
        List<FormatFilter> others = filters.stream()
                .filter(filter -> !(filter.getSourceTable().equalsIgnoreCase(field.getSourceTable())
                        && filter.getSourceColumn().equalsIgnoreCase(field.getSourceColumn())))
                .toList();
        RowFilterService.Condition condition = rowFilters.condition(others, "e");
        String entries = "SELECT e.BNP_ID FROM " + RowFilterService.ENTRY_TABLE + " e WHERE e.VERSION_ID = ?" + condition.and();
        List<Object> args = new ArrayList<>();
        args.add(version.getVersionId());
        args.addAll(condition.args());
        String column = "g." + SourceCatalogService.quote(source.name());
        String key = "g." + SourceCatalogService.quote(table.keyColumn());

        try {
            List<Object> blankArgs = new ArrayList<>(args);
            long blank = jdbc.queryForObject("SELECT COUNT(*) FROM " + RowFilterService.ENTRY_TABLE + " e WHERE e.VERSION_ID = ?"
                    + condition.and() + " AND NOT " + RowFilterService.exists(table, "e", column + " IS NOT NULL"),
                    Long.class, blankArgs.toArray());
            if ("text".equals(kind)) {
                StringBuilder sql = new StringBuilder("SELECT " + column + ", COUNT(DISTINCT " + key + ") FROM "
                        + table.qualifiedName() + " g WHERE " + column + " IS NOT NULL AND " + key + " IN (" + entries + ")");
                List<Object> valueArgs = new ArrayList<>(args);
                String search = StringUtils.trimToNull(request.search());
                if (search != null) {
                    sql.append(" AND UPPER(").append(column).append(") LIKE ? ESCAPE '\\'");
                    valueArgs.add("%" + RowFilterService.likeEscape(search.toUpperCase(Locale.ROOT)) + "%");
                }
                sql.append(" GROUP BY ").append(column).append(" ORDER BY ").append(column)
                        .append(" FETCH FIRST ").append(FACET_VALUES + 1).append(" ROWS ONLY");
                List<Value> values = jdbc.query(sql.toString(),
                        (rs, row) -> new Value(rs.getString(1), rs.getLong(2)), valueArgs.toArray());
                boolean more = values.size() > FACET_VALUES;
                return new Facet(kind, more ? values.subList(0, FACET_VALUES) : values, more, blank, null, null);
            }
            String[] range = jdbc.queryForObject("SELECT MIN(" + column + "), MAX(" + column + ") FROM "
                            + table.qualifiedName() + " g WHERE " + key + " IN (" + entries + ")",
                    (rs, row) -> new String[] {text(rs.getObject(1)), text(rs.getObject(2))}, args.toArray());
            return new Facet(kind, List.of(), false, blank, range[0], range[1]);
        } catch (DataAccessException ex) {
            throw unavailable(ex);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.time.temporal.TemporalAccessor temporal) {
            return java.time.LocalDate.from(temporal).toString();
        }
        if (value instanceof BigDecimal number) {
            return number.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private List<FormatField> fields(List<FormatFieldRequest> requested) {
        List<FormatField> fields = formatTemplateService.previewFields(requested);
        if (fields.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Pick at least one column to see the data");
        }
        return fields;
    }

    private List<Column> columns(List<SourceCatalogService.SourceTable> tables, List<FormatField> fields) {
        List<Column> columns = new ArrayList<>();
        for (FormatField field : fields) {
            SourceCatalogService.SourceColumn source = RowFilterService.table(tables, field.getSourceTable())
                    .column(field.getSourceColumn()).orElseThrow();
            columns.add(new Column(field.getHeaderName(), field.getSourceTable(), field.getSourceColumn(),
                    source.type(), source.kind()));
        }
        return columns;
    }

    /** The version asked for, or the first one operators can export. */
    private CertifiedVersion version(Long versionId) {
        List<CertifiedVersion> offered = versions.findAllByOrderByVersionIdAsc().stream()
                .filter(version -> version.isOperatorVisible() && "CERTIFIED".equals(version.getStatus()))
                .toList();
        if (offered.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No certified list version is available");
        }
        if (versionId == null) {
            return offered.get(0);
        }
        return offered.stream().filter(version -> versionId.equals(version.getVersionId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Choose a certified list version"));
    }

    private static ResponseStatusException unavailable(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause == null ? ex.getMessage() : cause.getMessage();
        return new ResponseStatusException(SERVICE_UNAVAILABLE,
                "The data could not be read" + (detail == null ? "" : ": " + StringUtils.abbreviate(detail, 300)));
    }
}
