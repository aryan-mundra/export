package com.bnpp.export.model;

import com.bnpp.export.entity.FormatField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A table group as one list uses it: the group, the list's columns per table (lower-case table name), and the tables
 * to read (those with columns, and every table between them and the root), root first.
 */
public record GroupSelection(ResolvedGroup resolved, Map<String, List<FormatField>> fieldsByTable, List<String> tables) {

    public TableGroup group() {
        return resolved.group();
    }

    /** The list's columns on one table, in list order. */
    public List<FormatField> fields(String table) {
        return fieldsByTable.getOrDefault(table.toLowerCase(Locale.ROOT), List.of());
    }

    /** Every column the list takes from this group, in list order. */
    public List<FormatField> fields() {
        List<FormatField> all = new ArrayList<>();
        fieldsByTable.values().forEach(all::addAll);
        all.sort(Comparator.comparingInt(FormatField::getPosition));
        return all;
    }

    /** The list's columns on one table without the group's key (written as the record's identifier instead). */
    public List<FormatField> valueFields(String table) {
        return fields(table).stream().filter(field -> !isKey(field)).toList();
    }

    public boolean reads(String table) {
        return tables.stream().anyMatch(name -> name.equalsIgnoreCase(table));
    }

    /** The list's columns on one table that carry a filter (values to keep). */
    public List<FormatField> filters(String table) {
        return fields(table).stream().filter(field -> field.getFilterValues() != null && !field.getFilterValues().isEmpty()).toList();
    }

    /** Whether this table or any table read below it has a filter. */
    public boolean filtersBelow(String table) {
        return !filters(table).isEmpty()
                || group().childrenOf(table).stream().filter(link -> reads(link.table())).anyMatch(link -> filtersBelow(link.table()));
    }

    /** The real name of a column of one of the group's tables. */
    public String column(String table, String column) {
        return resolved.column(table, column);
    }

    /** Whether a column is the group's common key (on the root table). */
    public boolean isKey(FormatField field) {
        TableGroup group = group();
        return group.isRoot(field.getSourceTable()) && group.root().key().equalsIgnoreCase(field.getSourceColumn());
    }
}
