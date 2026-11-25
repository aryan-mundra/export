package com.bnpp.export.service.impl;

import com.bnpp.export.model.TableGroup;
import com.bnpp.export.repository.SourceConstraintRepository;
import com.bnpp.export.repository.SourceConstraintRepository.Constraint;
import com.bnpp.export.util.XmlNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the table groups from the primary and foreign keys found in the source schema (no configuration file).
 *
 * <ul>
 *   <li>A <b>root</b> table has a primary key of one column and no foreign key to another table. Its primary key
 *       is the group's common key. One group per root table, in table-name order.</li>
 *   <li>A <b>related</b> table is one whose foreign key points at a table of the group. Following the foreign keys
 *       downwards gives several levels. A table belongs to one group only: when it points at tables of two groups,
 *       the first group (in name order) takes it.</li>
 *   <li>XML names come from the table names: the group and its rows use the table name, sections add a plural
 *       ending. A related table's primary key (when it is one column) identifies its rows, so Synthesis can say
 *       "this row changed" instead of "one row removed, one added".</li>
 *   <li>Left out, and logged: foreign keys of several columns, foreign keys into another schema or to something that
 *       is not a primary or unique key (their table is then a child that cannot be followed, never a group of its own), a table
 *       pointing at itself (ignored; the table may still be a root), and the module's own {@code ver_export_} tables.</li>
 * </ul>
 */
public final class TableGroupBuilder {

    private static final Logger log = LoggerFactory.getLogger(TableGroupBuilder.class);
    private static final String OWN_TABLES = "ver_export_";

    private TableGroupBuilder() {
    }

    /** One usable foreign key: {@code table.column} references {@code refTable.refColumn}. */
    private record Fk(String table, String column, String refTable, String refColumn) {
    }

    public static List<TableGroup> build(List<Constraint> constraints) {
        Map<String, String> schemaOf = new TreeMap<>();
        Map<String, List<String>> primaryKey = new TreeMap<>();
        Map<String, Map<String, List<Constraint>>> foreignKeys = new TreeMap<>();
        for (Constraint c : constraints) {
            if (c.table().toLowerCase(Locale.ROOT).startsWith(OWN_TABLES)) {
                continue;
            }
            schemaOf.putIfAbsent(c.table(), c.schema());
            if (SourceConstraintRepository.PRIMARY_KEY.equals(c.type())) {
                primaryKey.computeIfAbsent(c.table(), ignored -> new ArrayList<>()).add(c.column());
            } else if (SourceConstraintRepository.FOREIGN_KEY.equals(c.type())) {
                foreignKeys.computeIfAbsent(c.table(), ignored -> new TreeMap<>()).computeIfAbsent(c.name(), ignored -> new ArrayList<>()).add(c);
            }
        }
        Set<String> notRoots = new HashSet<>();   // tables with a foreign key we cannot follow: children, never a group of their own
        Map<String, List<Fk>> fksOf = usableForeignKeys(foreignKeys, notRoots);

        Set<String> claimed = new HashSet<>();
        List<TableGroup> groups = new ArrayList<>();
        for (var entry : primaryKey.entrySet()) {
            String root = entry.getKey();
            if (entry.getValue().size() == 1 && !fksOf.containsKey(root) && !notRoots.contains(root)) {
                claimed.add(root.toLowerCase(Locale.ROOT));
                groups.add(group(root, entry.getValue().get(0), schemaOf.get(root), primaryKey, fksOf, claimed));
            }
        }
        return List.copyOf(groups);
    }

    /** The foreign keys the module can follow, by table: one column, to another table of the same schema. */
    private static Map<String, List<Fk>> usableForeignKeys(Map<String, Map<String, List<Constraint>>> foreignKeys, Set<String> notRoots) {
        Map<String, List<Fk>> usable = new TreeMap<>();
        foreignKeys.forEach((table, byName) -> byName.forEach((name, columns) -> {
            Constraint first = columns.get(0);
            if (columns.size() > 1) {
                log.warn("Foreign key {} of {} has {} columns: not supported, ignored", name, table, columns.size());
                notRoots.add(table);
            } else if (first.refTable() == null || first.refColumn() == null) {
                log.warn("Foreign key {} of {} does not point at a primary or unique key: ignored", name, table);
                notRoots.add(table);
            } else if (first.refSchema() != null && !first.refSchema().equalsIgnoreCase(first.schema())) {
                log.warn("Foreign key {} of {} points into schema {}: ignored", name, table, first.refSchema());
                notRoots.add(table);
            } else if (first.refTable().equalsIgnoreCase(table)) {
                log.warn("Foreign key {} of {} points at its own table: ignored", name, table);
            } else if (first.refTable().toLowerCase(Locale.ROOT).startsWith(OWN_TABLES)) {
                notRoots.add(table);
            } else {
                usable.computeIfAbsent(table, ignored -> new ArrayList<>()).add(new Fk(table, first.column(), first.refTable(), first.refColumn()));
            }
        }));
        return usable;
    }

    /** One group: the root and, level by level, every unclaimed table whose foreign key points at a table of the group. */
    private static TableGroup group(String root, String rootKey, String schema, Map<String, List<String>> primaryKey,
                                    Map<String, List<Fk>> fksOf, Set<String> claimed) {
        List<TableGroup.Link> links = new ArrayList<>();
        Set<String> inGroup = new HashSet<>();
        inGroup.add(root.toLowerCase(Locale.ROOT));
        boolean grew = true;
        while (grew) {
            grew = false;
            Map<String, Fk> level = new LinkedHashMap<>();
            for (var entry : fksOf.entrySet()) {
                String lower = entry.getKey().toLowerCase(Locale.ROOT);
                if (claimed.contains(lower)) {
                    continue;
                }
                entry.getValue().stream().filter(fk -> inGroup.contains(fk.refTable().toLowerCase(Locale.ROOT))).findFirst()
                        .ifPresent(fk -> level.put(lower, fk));
            }
            for (var entry : level.entrySet()) {
                Fk fk = entry.getValue();
                List<String> pk = primaryKey.get(fk.table());
                String element = XmlNames.element(fk.table());
                links.add(new TableGroup.Link(fk.table(), fk.column(), fk.refTable(), fk.refColumn(), element, XmlNames.plural(element),
                        pk != null && pk.size() == 1 ? pk.get(0) : null));
                claimed.add(entry.getKey());
                grew = true;
            }
            inGroup.addAll(level.keySet());
        }
        String name = XmlNames.element(root);
        return new TableGroup(name, XmlNames.plural(name), schema, new TableGroup.Root(root, rootKey), List.copyOf(links));
    }
}
