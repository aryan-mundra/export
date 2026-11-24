package com.bnpp.export.service.impl;

import com.bnpp.export.entity.FormatField;
import com.bnpp.export.model.GroupRow;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.TableGroup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A group record as stored in a data version: canonical JSON {@code {"values": {header: value}, "_id": ...,
 * "tables": {table: [row, ...]}}}. Keys are sorted, empty values left out, related rows sorted (by their id when the
 * groups file gives one, else by content), so equal data always gives equal JSON and equal hashes.
 */
final class RecordDocuments {

    /** The row id of a related row, when its table has one in the groups file. */
    static final String ID = "_id";
    static final String VALUES = "values";
    static final String TABLES = "tables";

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private RecordDocuments() {
    }

    /** A row of {@code table} (and its related rows) as a document. */
    static Map<String, Object> build(GroupSelection selection, String table, GroupRow row) {
        Map<String, Object> doc = new TreeMap<>();
        Map<String, Object> values = new TreeMap<>();
        for (FormatField field : selection.valueFields(table)) {
            String value = text(row.get(selection.column(table, field.getSourceColumn())));
            if (!value.isEmpty()) {
                values.put(field.getHeaderName(), value);
            }
        }
        doc.put(VALUES, values);
        selection.group().link(table).map(TableGroup.Link::id)
                .ifPresent(id -> doc.put(ID, text(row.get(selection.column(table, id)))));
        Map<String, Object> tables = new TreeMap<>();
        for (TableGroup.Link link : selection.group().childrenOf(table)) {
            List<GroupRow> children = row.children(link.table());
            if (children.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (GroupRow child : children) {
                rows.add(build(selection, link.table(), child));
            }
            rows.sort(Comparator.comparing(RecordDocuments::sortKey));
            tables.put(link.table(), rows);
        }
        if (!tables.isEmpty()) {
            doc.put(TABLES, tables);
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> values(Map<String, Object> doc) {
        return (Map<String, Object>) doc.getOrDefault(VALUES, Map.of());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> doc, String table) {
        Map<String, Object> tables = (Map<String, Object>) doc.getOrDefault(TABLES, Map.of());
        return (List<Map<String, Object>>) tables.getOrDefault(table, List.of());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> tables(Map<String, Object> doc) {
        return (Map<String, Object>) doc.getOrDefault(TABLES, Map.of());
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A stored record could not be read", ex);
        }
    }

    static <T> T read(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored data could not be read", ex);
        }
    }

    private static String sortKey(Map<String, Object> row) {
        Object id = row.get(ID);
        return id != null ? "0" + String.format("%40s", id) : "1" + write(row);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
