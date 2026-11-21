package com.bnpp.export.service;

import com.bnpp.export.model.FormatField;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adds golden-source values to list records. Every gs_ table is matched to the list entry on BNP_ID,
 * the key all gs_ tables share. When a table has several matching rows for one entry, the
 * distinct values are joined with "; " in a fixed order.
 *
 * Called once per batch while a file streams, so it only ever holds one batch.
 */
@Component
public class GoldenSourceEnricher {

    /** Keeps each IN list short, so the statement stays small and its plan reusable. */
    private static final int CHUNK = 500;

    private final SourceCatalogService catalog;

    public GoldenSourceEnricher(SourceCatalogService catalog) {
        this.catalog = catalog;
    }

    public List<Map<String, Object>> enrich(List<Map<String, Object>> records, List<FormatField> fields) {
        List<FormatField> goldenSource = fields.stream().filter(FormatField::isGoldenSource).toList();
        if (goldenSource.isEmpty() || records.isEmpty()) {
            return records;
        }
        List<SourceCatalogService.SourceTable> tables = catalog.tables();
        Map<Link, List<FormatField>> byLink = new LinkedHashMap<>();
        for (FormatField field : goldenSource) {
            SourceCatalogService.SourceTable table = tables.stream()
                    .filter(candidate -> candidate.name().equalsIgnoreCase(field.getSourceTable()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Source table " + field.getSourceTable() + " no longer exists"));
            byLink.computeIfAbsent(link(table, field), ignored -> new ArrayList<>()).add(field);
        }
        List<Map<String, Object>> enriched = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            enriched.add(new LinkedHashMap<>(record));
        }
        for (Map.Entry<Link, List<FormatField>> entry : byLink.entrySet()) {
            Link link = entry.getKey();
            List<String> columns = new ArrayList<>();
            for (FormatField field : entry.getValue()) {
                columns.add(link.table().column(field.getSourceColumn())
                        .orElseThrow(() -> new IllegalStateException(
                                "Column " + field.getSourceColumn() + " no longer exists on " + link.table().name()))
                        .name());
            }
            Map<String, Map<String, Set<String>>> values = load(link, columns, enriched);
            for (Map<String, Object> record : enriched) {
                String key = link.key(record.get(link.field()));
                Map<String, Set<String>> row = key == null ? Map.of() : values.getOrDefault(key, Map.of());
                for (FormatField field : entry.getValue()) {
                    Set<String> found = row.getOrDefault(field.getSourceColumn().toLowerCase(Locale.ROOT), Collections.emptySet());
                    record.put(field.recordKey(), found.isEmpty() ? null : String.join("; ", found));
                }
            }
        }
        return enriched;
    }

    /** Every gs_ table is matched on its BNP_ID column to the list entry's BNP_ID. */
    private Link link(SourceCatalogService.SourceTable table, FormatField field) {
        String columnName = table.keyColumn();
        if (columnName == null) {
            throw new IllegalStateException(table.name() + " no longer has a " + SourceCatalogService.KEY + " column");
        }
        SourceCatalogService.SourceColumn column = table.column(columnName).orElseThrow();
        return new Link(table, column, SourceCatalogService.RECORD_KEY);
    }

    private Map<String, Map<String, Set<String>>> load(Link link, List<String> columns, List<Map<String, Object>> records) {
        // Distinct link values of this batch, typed to suit the column so the database can use its index.
        Map<String, Object> keys = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            Object raw = record.get(link.field());
            String key = link.key(raw);
            if (key != null) {
                keys.putIfAbsent(key, link.numeric() ? new BigDecimal(key) : key);
            }
        }
        Map<String, Map<String, Set<String>>> values = new HashMap<>();
        if (keys.isEmpty()) {
            return values;
        }
        String keyColumn = SourceCatalogService.quote(link.column().name());
        StringBuilder select = new StringBuilder("SELECT ").append(keyColumn);
        StringBuilder orderBy = new StringBuilder(" ORDER BY ").append(keyColumn);
        for (String column : columns) {
            select.append(", ").append(SourceCatalogService.quote(column));
            orderBy.append(", ").append(SourceCatalogService.quote(column));
        }
        select.append(" FROM ").append(link.table().qualifiedName()).append(" WHERE ").append(keyColumn).append(" IN (");
        List<Object> binds = new ArrayList<>(keys.values());
        for (int start = 0; start < binds.size(); start += CHUNK) {
            List<Object> chunk = binds.subList(start, Math.min(binds.size(), start + CHUNK));
            String sql = select + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")" + orderBy;
            catalog.jdbc().query(sql, rs -> {
                String key = link.key(rs.getObject(1));
                if (key == null) {
                    return;
                }
                Map<String, Set<String>> row = values.computeIfAbsent(key, ignored -> new HashMap<>());
                for (int index = 0; index < columns.size(); index++) {
                    Object value = rs.getObject(index + 2);
                    if (value != null) {
                        row.computeIfAbsent(columns.get(index).toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>())
                                .add(String.valueOf(value));
                    }
                }
            }, chunk.toArray());
        }
        return values;
    }

    /** A gs_ table linked to the record: table.column = record.field. */
    private record Link(SourceCatalogService.SourceTable table, SourceCatalogService.SourceColumn column, String field) {

        boolean numeric() {
            return column.numeric();
        }

        /**
         * The value both sides are compared on: numbers in plain form (so 1001 and 1001.0 match),
         * text as is. Null when the value cannot match this column (empty, or not a number for a number column).
         */
        String key(Object value) {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            if (!numeric()) {
                return text.isEmpty() ? null : text;
            }
            try {
                return new BigDecimal(text.trim()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Link link
                    && table.name().equalsIgnoreCase(link.table.name())
                    && column.name().equalsIgnoreCase(link.column.name())
                    && field.equals(link.field);
        }

        @Override
        public int hashCode() {
            return (table.name().toLowerCase(Locale.ROOT) + "." + column.name().toLowerCase(Locale.ROOT) + "=" + field).hashCode();
        }
    }
}
