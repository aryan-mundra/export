package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFilterRequest;
import com.bnpp.export.model.FormatField;
import com.bnpp.export.model.FormatFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Row filters of a template: which list entries go in the file.
 *
 * A filter tests one gs_ column of the template the way its cell shows it. A list entry passes when
 * any of its gs_ rows matches ("is one of", "contains", comparisons, date ranges) or, for the
 * negative forms ("is not one of", "≠"), when none does. An entry with no value in the column has an
 * empty cell. All filters of a template must pass.
 *
 * Every check is written as SQL on the list entry table, so the data explorer, the review counts
 * and the export (one query per batch) all apply exactly the same rule.
 */
@Service
public class RowFilterService {

    /** The list entries of each certified version. */
    public static final String ENTRY_TABLE = "VER_VERSION_MEMBER";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_VALUES = 1000;
    private static final int CHUNK = 500;

    private static final Map<String, Set<String>> OPS = Map.of(
            "text", Set.of("in", "not_in", "contains", "empty", "not_empty"),
            "number", Set.of("eq", "ne", "gt", "gte", "lt", "lte", "between", "empty", "not_empty"),
            "date", Set.of("between", "empty", "not_empty"));

    private final SourceCatalogService catalog;

    public RowFilterService(SourceCatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * Checks requested filters against the template's (already checked) columns and returns them as
     * unsaved rows, with table and column names as the database spells them.
     */
    public List<FormatFilter> validate(List<FormatFilterRequest> requested, List<FormatField> templateFields) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<SourceCatalogService.SourceTable> tables = catalog.tables();
        Set<String> seen = new HashSet<>();
        List<FormatFilter> filters = new ArrayList<>();
        int position = 1;
        for (FormatFilterRequest request : requested) {
            String table = StringUtils.trimToEmpty(request.sourceTable());
            String column = StringUtils.trimToEmpty(request.sourceColumn());
            FormatField field = templateFields.stream()
                    .filter(candidate -> candidate.getSourceTable() != null
                            && candidate.getSourceTable().equalsIgnoreCase(table)
                            && candidate.getSourceColumn().equalsIgnoreCase(column))
                    .findFirst()
                    .orElseThrow(() -> bad("A filter on " + table + "." + column + " needs that column on the template"));
            String label = field.getSourceTable() + "." + field.getSourceColumn();
            if (!seen.add(label.toLowerCase(Locale.ROOT))) {
                throw bad("Only one filter per column: " + label + " has two");
            }
            SourceCatalogService.SourceColumn source = column(tables, field.getSourceTable(), field.getSourceColumn());
            String kind = source.kind();
            String op = StringUtils.trimToEmpty(request.op()).toLowerCase(Locale.ROOT);
            Set<String> allowed = OPS.get(kind);
            if (allowed == null) {
                throw bad(label + " cannot be filtered (" + source.type() + " columns are not supported)");
            }
            if (!allowed.contains(op)) {
                throw bad("\"" + op + "\" is not a filter for the " + kind + " column " + label);
            }
            boolean blank = Boolean.TRUE.equals(request.blank());
            List<String> values = checkValues(label, kind, op, request.values(), blank);

            FormatFilter filter = new FormatFilter();
            filter.setSourceTable(field.getSourceTable());
            filter.setSourceColumn(field.getSourceColumn());
            filter.setOperator(op);
            filter.setValuesJson(toJson(values));
            filter.setIncludeBlank(blank && (op.equals("in") || op.equals("not_in")));
            filter.setPosition(position++);
            filters.add(filter);
        }
        return filters;
    }

    private List<String> checkValues(String label, String kind, String op, List<String> raw, boolean blank) {
        List<String> values = raw == null ? List.of() : raw;
        switch (op) {
            case "empty", "not_empty" -> {
                return List.of();
            }
            case "in", "not_in" -> {
                List<String> kept = values.stream().filter(value -> value != null && !value.isEmpty()).distinct().toList();
                if (kept.isEmpty() && !blank) {
                    throw bad("Choose at least one value to filter " + label);
                }
                if (kept.size() > MAX_VALUES) {
                    throw bad("A filter can list at most " + MAX_VALUES + " values");
                }
                return kept;
            }
            case "contains" -> {
                String text = values.isEmpty() ? null : values.get(0);
                if (text == null || text.isEmpty()) {
                    throw bad("Type the text that " + label + " must contain");
                }
                return List.of(text);
            }
            case "between" -> {
                String from = values.size() > 0 ? StringUtils.trimToNull(values.get(0)) : null;
                String to = values.size() > 1 ? StringUtils.trimToNull(values.get(1)) : null;
                if ("number".equals(kind)) {
                    if (from == null || to == null) {
                        throw bad("Enter both numbers of the range for " + label);
                    }
                    BigDecimal low = number(label, from);
                    BigDecimal high = number(label, to);
                    if (low.compareTo(high) > 0) {
                        throw bad("The range for " + label + " starts after it ends");
                    }
                    return List.of(plain(low), plain(high));
                }
                if (from == null && to == null) {
                    throw bad("Enter a start date, an end date, or both for " + label);
                }
                LocalDate start = from == null ? null : date(label, from);
                LocalDate end = to == null ? null : date(label, to);
                if (start != null && end != null && start.isAfter(end)) {
                    throw bad("The date range for " + label + " starts after it ends");
                }
                List<String> range = new ArrayList<>();
                range.add(start == null ? "" : start.toString());
                range.add(end == null ? "" : end.toString());
                return range;
            }
            default -> {
                String value = values.isEmpty() ? null : StringUtils.trimToNull(values.get(0));
                if (value == null) {
                    throw bad("Enter a number to compare " + label + " with");
                }
                return List.of(plain(number(label, value)));
            }
        }
    }

    public List<String> values(FormatFilter filter) {
        if (filter.getValuesJson() == null || filter.getValuesJson().isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(filter.getValuesJson(), new TypeReference<List<String>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Filter " + filter.getId() + " has unreadable values", ex);
        }
    }

    /** The filter in words, e.g. {@code gs_address.city is Paris, London}. */
    public String describe(FormatFilter filter) {
        String label = filter.getSourceTable() + "." + filter.getSourceColumn();
        return label + " " + phrase(filter.getOperator(), values(filter), filter.isIncludeBlank());
    }

    static String phrase(String op, List<String> values, boolean blank) {
        String first = values.isEmpty() ? "" : values.get(0);
        String second = values.size() > 1 ? values.get(1) : "";
        return switch (op) {
            case "in" -> values.isEmpty() ? "is empty" : "is " + list(values) + (blank ? " or empty" : "");
            case "not_in" -> values.isEmpty() ? "is not empty" : "is not " + list(values) + (blank ? ", and not empty" : "");
            case "contains" -> "contains \"" + first + "\"";
            case "eq" -> "= " + first;
            case "ne" -> "≠ " + first;
            case "gt" -> "> " + first;
            case "gte" -> "≥ " + first;
            case "lt" -> "< " + first;
            case "lte" -> "≤ " + first;
            case "between" -> {
                if (first.isEmpty()) {
                    yield "is on or before " + second;
                }
                if (second.isEmpty()) {
                    yield "is on or after " + first;
                }
                yield (first.matches("\\d{4}-\\d{2}-\\d{2}") ? "is from " + first + " to " : "is between " + first + " and ") + second;
            }
            case "empty" -> "is empty";
            case "not_empty" -> "is not empty";
            default -> op;
        };
    }

    private static String list(List<String> values) {
        int shown = Math.min(values.size(), 8);
        String text = String.join(", ", values.subList(0, shown));
        return values.size() > shown ? text + " and " + (values.size() - shown) + " more" : text;
    }

    /** An SQL condition on a list entry row (with its alias), and the values to bind, in order. */
    public record Condition(String sql, List<Object> args) {

        public static final Condition NONE = new Condition("", List.of());

        public boolean isEmpty() {
            return sql.isEmpty();
        }

        /** " AND (...)" to append to a WHERE clause, or nothing. */
        public String and() {
            return isEmpty() ? "" : " AND (" + sql + ")";
        }
    }

    /** All filters as one condition on the list entry row {@code alias}; each must pass. */
    public Condition condition(List<FormatFilter> filters, String alias) {
        if (filters == null || filters.isEmpty()) {
            return Condition.NONE;
        }
        List<SourceCatalogService.SourceTable> tables = catalog.tables();
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (FormatFilter filter : filters) {
            parts.add(part(tables, filter, alias, args));
        }
        return new Condition(String.join(" AND ", parts), List.copyOf(args));
    }

    private String part(List<SourceCatalogService.SourceTable> tables, FormatFilter filter, String alias, List<Object> args) {
        SourceCatalogService.SourceTable table = table(tables, filter.getSourceTable());
        SourceCatalogService.SourceColumn source = table.column(filter.getSourceColumn())
                .orElseThrow(() -> new IllegalStateException(
                        "Column " + filter.getSourceColumn() + " no longer exists on " + table.name()));
        String column = "g." + SourceCatalogService.quote(source.name());
        List<String> values = values(filter);
        String kind = source.kind();
        return switch (filter.getOperator()) {
            case "in" -> {
                List<String> any = new ArrayList<>();
                if (!values.isEmpty()) {
                    any.add(exists(table, alias, column + " IN (" + marks(values.size()) + ")"));
                    args.addAll(values);
                }
                if (filter.isIncludeBlank()) {
                    any.add("NOT " + exists(table, alias, column + " IS NOT NULL"));
                }
                yield "(" + String.join(" OR ", any) + ")";
            }
            case "not_in" -> {
                List<String> all = new ArrayList<>();
                if (!values.isEmpty()) {
                    all.add("NOT " + exists(table, alias, column + " IN (" + marks(values.size()) + ")"));
                    args.addAll(values);
                }
                if (filter.isIncludeBlank()) {
                    all.add(exists(table, alias, column + " IS NOT NULL"));
                }
                yield "(" + String.join(" AND ", all) + ")";
            }
            case "contains" -> {
                args.add("%" + likeEscape(values.get(0).toUpperCase(Locale.ROOT)) + "%");
                yield exists(table, alias, "UPPER(" + column + ") LIKE ? ESCAPE '\\'");
            }
            case "empty" -> "NOT " + exists(table, alias, column + " IS NOT NULL");
            case "not_empty" -> exists(table, alias, column + " IS NOT NULL");
            case "between" -> {
                if ("number".equals(kind)) {
                    args.add(new BigDecimal(values.get(0)));
                    args.add(new BigDecimal(values.get(1)));
                    yield exists(table, alias, column + " >= ? AND " + column + " <= ?");
                }
                List<String> range = new ArrayList<>();
                if (!values.get(0).isEmpty()) {
                    range.add(column + " >= ?");
                    args.add(java.sql.Date.valueOf(LocalDate.parse(values.get(0))));
                }
                if (values.size() > 1 && !values.get(1).isEmpty()) {
                    // Before the next day, so a timestamp on the last day still counts.
                    range.add(column + " < ?");
                    args.add(java.sql.Date.valueOf(LocalDate.parse(values.get(1)).plusDays(1)));
                }
                yield exists(table, alias, String.join(" AND ", range));
            }
            case "ne" -> {
                args.add(new BigDecimal(values.get(0)));
                yield "NOT " + exists(table, alias, column + " = ?");
            }
            default -> {
                String operator = switch (filter.getOperator()) {
                    case "eq" -> "=";
                    case "gt" -> ">";
                    case "gte" -> ">=";
                    case "lt" -> "<";
                    case "lte" -> "<=";
                    default -> throw new IllegalStateException("Unknown filter " + filter.getOperator());
                };
                args.add(new BigDecimal(values.get(0)));
                yield exists(table, alias, column + " " + operator + " ?");
            }
        };
    }

    /** Rows of the gs_ table that belong to the list entry {@code alias} and meet the condition. */
    static String exists(SourceCatalogService.SourceTable table, String alias, String condition) {
        return "EXISTS (SELECT 1 FROM " + table.qualifiedName() + " g WHERE g."
                + SourceCatalogService.quote(table.keyColumn()) + " = " + alias + ".BNP_ID AND " + condition + ")";
    }

    /**
     * Keeps the records of a batch that pass the filters, in their order: one query per 500 records
     * on the list entry table, by record id.
     */
    public List<Map<String, Object>> keep(Long versionId, List<Map<String, Object>> records, Condition condition) {
        if (condition.isEmpty() || records.isEmpty()) {
            return records;
        }
        List<Long> ids = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Long id = id(record.get("id"));
            if (id != null) {
                ids.add(id);
            }
        }
        Set<Long> passed = new HashSet<>();
        for (int start = 0; start < ids.size(); start += CHUNK) {
            List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + CHUNK));
            List<Object> args = new ArrayList<>();
            args.add(versionId);
            args.addAll(chunk);
            args.addAll(condition.args());
            String sql = "SELECT e.RECORD_ID FROM " + ENTRY_TABLE + " e WHERE e.VERSION_ID = ? AND e.RECORD_ID IN ("
                    + marks(chunk.size()) + ")" + condition.and();
            catalog.jdbc().query(sql, rs -> {
                passed.add(rs.getLong(1));
            }, args.toArray());
        }
        if (passed.isEmpty()) {
            return List.of();
        }
        return records.stream().filter(record -> passed.contains(id(record.get("id")))).toList();
    }

    private static Long id(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String marks(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    static String likeEscape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static SourceCatalogService.SourceTable table(List<SourceCatalogService.SourceTable> tables, String name) {
        SourceCatalogService.SourceTable table = tables.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Source table " + name + " no longer exists"));
        if (table.keyColumn() == null) {
            throw new IllegalStateException(table.name() + " no longer has a " + SourceCatalogService.KEY + " column");
        }
        return table;
    }

    private static SourceCatalogService.SourceColumn column(List<SourceCatalogService.SourceTable> tables, String table, String column) {
        return table(tables, table).column(column)
                .orElseThrow(() -> bad("Unknown column " + column + " on " + table));
    }

    private static BigDecimal number(String label, String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            throw bad("\"" + value + "\" is not a number (filter on " + label + ")");
        }
    }

    private static LocalDate date(String label, String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw bad("\"" + value + "\" is not a date as YYYY-MM-DD (filter on " + label + ")");
        }
    }

    private static String plain(BigDecimal number) {
        return number.stripTrailingZeros().toPlainString();
    }

    private static String toJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }
}
