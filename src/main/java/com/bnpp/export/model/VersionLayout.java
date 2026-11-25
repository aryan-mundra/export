package com.bnpp.export.model;

import java.util.List;

/**
 * What a data version was built from: its groups in order, with their tables, columns and XML names at that time.
 * Stored with every version, so it can be read, compared and written as XML after the source tables or their foreign keys changed.
 */
public record VersionLayout(List<Group> groups) {

    public Group group(String name) {
        return groups.stream().filter(group -> group.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * @param keyColumn    the group's key column (database name)
     * @param keyAttribute the XML attribute holding the key, e.g. person_id in {@code <Person person_id="101">}
     */
    public record Group(String name, String section, String keyColumn, String keyAttribute, String rootTable, List<Table> tables) {

        public Table table(String name) {
            return tables.stream().filter(table -> table.table().equalsIgnoreCase(name)).findFirst().orElse(null);
        }

        /** The tables nested directly under {@code table}. */
        public List<Table> childrenOf(String table) {
            return tables.stream().filter(candidate -> candidate.parent() != null && candidate.parent().equalsIgnoreCase(table)).toList();
        }
    }

    /**
     * @param parent  the table it is nested under (null for the root)
     * @param element the XML element of one row (for the root: the group's record element)
     * @param section the XML element around its rows (for the root: the group's section)
     * @param hasId   whether its rows carry an id (matched on it when versions are compared)
     * @param headers its columns on the list, in order (the key excluded)
     */
    public record Table(String table, String parent, String element, String section, boolean hasId, List<String> headers) {
    }
}
