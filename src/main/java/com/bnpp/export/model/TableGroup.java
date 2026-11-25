package com.bnpp.export.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One group of related tables, discovered from the primary and foreign keys of the source schema (see TableGroupBuilder): a root table, one element per
 * root row, and related tables joined to it (or to each other) by foreign key.
 *
 * @param name    the XML element of one record, e.g. {@code Person}
 * @param section the XML element around all records of the group, e.g. {@code Persons}
 * @param schema  the schema of the group's tables, or null for the database's search_path
 * @param root    the root table and the group's common key
 * @param links   the related tables, each with its foreign key
 */
public record TableGroup(String name, String section, String schema, Root root, List<Link> links) {

    /** The root table: one record per row; {@code key} is the group's common key. */
    public record Root(String table, String key) {
    }

    /**
     * A related table: {@code table.key} references {@code refTable.refColumn}, where refTable is the root or another
     * related table of the same group.
     *
     * @param element the XML element of one of its rows, e.g. {@code Address}
     * @param section the XML element around its rows, e.g. {@code Addresses}
     * @param id      optional: the column that identifies one of its rows (its primary key), so comparing two
     *                versions can say "this row changed" instead of "one row removed, one added"; null when not set
     */
    public record Link(String table, String key, String refTable, String refColumn, String element, String section, String id) {
    }

    /** Every table of the group: the root first, then the related tables in file order. */
    public List<String> tables() {
        List<String> tables = new ArrayList<>();
        tables.add(root.table());
        links.forEach(link -> tables.add(link.table()));
        return tables;
    }

    public boolean has(String table) {
        return table != null && tables().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(table));
    }

    public boolean isRoot(String table) {
        return root.table().equalsIgnoreCase(table);
    }

    public Optional<Link> link(String table) {
        return links.stream().filter(link -> link.table().equalsIgnoreCase(table)).findFirst();
    }

    /** The related tables directly under {@code table} (those whose foreign key references it). */
    public List<Link> childrenOf(String table) {
        return links.stream().filter(link -> link.refTable().equalsIgnoreCase(table)).toList();
    }

    /** The column of {@code table} that joins it into the group: the key for the root, the foreign key otherwise. */
    public String joinColumn(String table) {
        return isRoot(table) ? root.key() : link(table).map(Link::key).orElse(null);
    }

    /** "table.column" that {@code table} joins to: the root's own key, or what its foreign key references. */
    public String joinTarget(String table) {
        return isRoot(table) ? root.table() + "." + root.key()
                : link(table).map(link -> link.refTable() + "." + link.refColumn()).orElse(null);
    }

}
