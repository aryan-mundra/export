package com.bnpp.export.dto;

import java.util.List;

/** What changed between two data versions of a list, per group. */
public record SynthesisSummaryResponse(DataVersionResponse before, DataVersionResponse after, List<Group> groups) {

    /**
     * @param tables         per table (the root first): records touched there, rows added and removed, values changed
     * @param columns        per column: how many values changed, most changed first
     * @param columnsAdded   columns ("table.header") in the newer version only
     * @param columnsRemoved columns in the older version only
     */
    public record Group(String group, long before, long after, long added, long removed, long changed, long unchanged,
                        List<Table> tables, List<Column> columns, List<String> columnsAdded, List<String> columnsRemoved) {
    }

    /** @param valuesChanged values changed in rows matched across the versions (same id, or the record's own values) */
    public record Table(String table, String label, boolean root, long records, long rowsAdded, long rowsRemoved, long valuesChanged) {
    }

    public record Column(String table, String column, long changed) {
    }
}
