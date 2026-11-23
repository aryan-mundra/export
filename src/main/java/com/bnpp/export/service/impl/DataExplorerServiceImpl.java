package com.bnpp.export.service.impl;

import com.bnpp.export.dto.ExplorePageResponse;
import com.bnpp.export.dto.ExploreRequest;
import com.bnpp.export.dto.FacetRequest;
import com.bnpp.export.dto.FacetResponse;
import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatFilter;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ServiceUnavailableException;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.service.DataExplorerService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.GoldenSourceService;
import com.bnpp.export.service.RowFilterService;
import com.bnpp.export.service.SourceCatalogService;
import com.bnpp.export.util.SqlUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/** Implementation of {@link DataExplorerService}. */
@Service
public class DataExplorerServiceImpl implements DataExplorerService {

    private final FormatTemplateService formatTemplateService;
    private final RowFilterService rowFilters;
    private final GoldenSourceService enricher;
    private final SourceCatalogService catalog;
    private final CertifiedVersionRepository versions;
    private final JdbcTemplate jdbc;

    public DataExplorerServiceImpl(FormatTemplateService formatTemplateService,
                               RowFilterService rowFilters,
                               GoldenSourceService enricher,
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

    @Override
    public ExplorePageResponse explore(ExploreRequest request) {
        CertifiedVersion version = version(request.versionId());
        List<FormatField> fields = fields(request.fields());
        List<FormatFilter> filters = rowFilters.validate(request.filters(), fields);
        List<SourceCatalogService.SourceTable> tables = catalog.tables();
        List<ExplorePageResponse.Column> columns = columns(tables, fields);
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
            SourceCatalogService.SourceTable table = SqlUtils.sourceTable(tables, field.getSourceTable());
            String column = SqlUtils.quote(table.column(field.getSourceColumn()).orElseThrow().name());
            String direction = "desc".equalsIgnoreCase(request.sortDirection()) ? "DESC" : "ASC";
            order = " ORDER BY (SELECT MIN(g." + column + ") FROM " + table.qualifiedName() + " g WHERE g."
                    + SqlUtils.quote(table.keyColumn()) + " = e.BNP_ID) " + direction + " NULLS LAST, e.RECORD_ID";
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
            List<ExplorePageResponse.Row> rows = new ArrayList<>();
            for (Map<String, Object> record : enricher.enrich(records, fields)) {
                List<String> cells = new ArrayList<>(fields.size());
                for (FormatField field : fields) {
                    Object value = record.get(field.recordKey());
                    cells.add(value == null ? null : String.valueOf(value));
                }
                rows.add(new ExplorePageResponse.Row(((Number) record.get("id")).longValue(), cells));
            }
            Long total = null;
            Long entries = null;
            if (!Boolean.FALSE.equals(request.withTotal())) {
                total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
                entries = condition.isEmpty() ? total : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + RowFilterService.ENTRY_TABLE + " e WHERE e.VERSION_ID = ?",
                        Long.class, version.getVersionId());
            }
            return new ExplorePageResponse(version.getVersionId(), version.getListName(), columns, total, entries, offset, rows, more);
        } catch (DataAccessException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public FacetResponse facet(FacetRequest request) {
        CertifiedVersion version = version(request.versionId());
        List<FormatField> fields = fields(request.fields());
        List<FormatFilter> filters = rowFilters.validate(request.filters(), fields);
        Integer index = request.column();
        if (index == null || index < 0 || index >= fields.size()) {
            throw new BadRequestException("Choose a column of the list");
        }
        FormatField field = fields.get(index);
        List<SourceCatalogService.SourceTable> tables = catalog.tables();
        SourceCatalogService.SourceTable table = SqlUtils.sourceTable(tables, field.getSourceTable());
        SourceCatalogService.SourceColumn source = table.column(field.getSourceColumn()).orElseThrow();
        String kind = source.kind();
        if ("other".equals(kind)) {
            return new FacetResponse(kind, List.of(), false, 0, null, null);
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
        String column = "g." + SqlUtils.quote(source.name());
        String key = "g." + SqlUtils.quote(table.keyColumn());

        try {
            List<Object> blankArgs = new ArrayList<>(args);
            long blank = jdbc.queryForObject("SELECT COUNT(*) FROM " + RowFilterService.ENTRY_TABLE + " e WHERE e.VERSION_ID = ?"
                    + condition.and() + " AND NOT " + SqlUtils.exists(table, "e", column + " IS NOT NULL"),
                    Long.class, blankArgs.toArray());
            if ("text".equals(kind)) {
                StringBuilder sql = new StringBuilder("SELECT " + column + ", COUNT(DISTINCT " + key + ") FROM "
                        + table.qualifiedName() + " g WHERE " + column + " IS NOT NULL AND " + key + " IN (" + entries + ")");
                List<Object> valueArgs = new ArrayList<>(args);
                String search = StringUtils.trimToNull(request.search());
                if (search != null) {
                    sql.append(" AND UPPER(").append(column).append(") LIKE ? ESCAPE '\\'");
                    valueArgs.add("%" + SqlUtils.likeEscape(search.toUpperCase(Locale.ROOT)) + "%");
                }
                sql.append(" GROUP BY ").append(column).append(" ORDER BY ").append(column)
                        .append(" FETCH FIRST ").append(FACET_VALUES + 1).append(" ROWS ONLY");
                List<FacetResponse.Value> values = jdbc.query(sql.toString(),
                        (rs, row) -> new FacetResponse.Value(rs.getString(1), rs.getLong(2)), valueArgs.toArray());
                boolean more = values.size() > FACET_VALUES;
                return new FacetResponse(kind, more ? values.subList(0, FACET_VALUES) : values, more, blank, null, null);
            }
            String[] range = jdbc.queryForObject("SELECT MIN(" + column + "), MAX(" + column + ") FROM "
                            + table.qualifiedName() + " g WHERE " + key + " IN (" + entries + ")",
                    (rs, row) -> new String[] {text(rs.getObject(1)), text(rs.getObject(2))}, args.toArray());
            return new FacetResponse(kind, List.of(), false, blank, range[0], range[1]);
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
            throw new BadRequestException("Pick at least one column to see the data");
        }
        return fields;
    }

    private List<ExplorePageResponse.Column> columns(List<SourceCatalogService.SourceTable> tables, List<FormatField> fields) {
        List<ExplorePageResponse.Column> columns = new ArrayList<>();
        for (FormatField field : fields) {
            SourceCatalogService.SourceColumn source = SqlUtils.sourceTable(tables, field.getSourceTable())
                    .column(field.getSourceColumn()).orElseThrow();
            columns.add(new ExplorePageResponse.Column(field.getHeaderName(), field.getSourceTable(), field.getSourceColumn(),
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
            throw new BadRequestException("No certified list version is available");
        }
        if (versionId == null) {
            return offered.get(0);
        }
        return offered.stream().filter(version -> versionId.equals(version.getVersionId())).findFirst()
                .orElseThrow(() -> new BadRequestException("Choose a certified list version"));
    }

    private static ServiceUnavailableException unavailable(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause == null ? ex.getMessage() : cause.getMessage();
        return new ServiceUnavailableException("The data could not be read" + (detail == null ? "" : ": " + StringUtils.abbreviate(detail, 300)));
    }
}
