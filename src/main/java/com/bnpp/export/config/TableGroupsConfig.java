package com.bnpp.export.config;

import com.bnpp.export.exception.ServiceUnavailableException;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.util.XmlNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The table groups of {@code config/table-groups.yml} (or the file named by {@code export.groups.file}): which tables
 * the module reads, and how they are joined (each related table's foreign key).
 *
 * <p>The file is read again whenever it changes, so a new group needs no restart. It is checked when read: a group
 * needs a name, a root table and key; each related table a key and a {@code references: table.column} that names
 * the root or another table of the same group; a table belongs to one group only. Whether the tables and columns
 * exist in the database is checked by the table group service.
 */
@Component
public class TableGroupsConfig {

    private static final Logger log = LoggerFactory.getLogger(TableGroupsConfig.class);

    private final Path file;
    private FileTime loadedAt;
    private List<TableGroup> groups = List.of();
    private String error;

    public TableGroupsConfig(@Value("${export.groups.file:config/table-groups.yml}") String file) {
        this.file = Path.of(file);
    }

    /** Whether the groups file is present. */
    public boolean enabled() {
        return Files.isRegularFile(file);
    }

    /** The groups, in file order. Fails with the file's problem when it cannot be used. */
    public synchronized List<TableGroup> groups() {
        reloadIfChanged();
        if (error != null) {
            throw new ServiceUnavailableException(error);
        }
        return groups;
    }

    private void reloadIfChanged() {
        if (!Files.isRegularFile(file)) {
            groups = List.of();
            error = "The table groups file " + file.toAbsolutePath() + " was not found";
            loadedAt = null;
            return;
        }
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (modified.equals(loadedAt)) {
                return;
            }
            loadedAt = modified;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                groups = parse(new Yaml(new SafeConstructor(new LoaderOptions())).load(reader));
                error = null;
                log.info("Table groups read from {}: {}", file.toAbsolutePath(),
                        String.join(", ", groups.stream().map(TableGroup::name).toList()));
            }
        } catch (IOException | RuntimeException ex) {
            groups = List.of();
            error = "Table groups file " + file + ": " + (ex instanceof GroupsFileException ? ex.getMessage()
                    : ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.error(error);
        }
    }

    // ------------------------------------------------------------------ reading and checking the file

    /** Reads and checks a parsed groups file. */
    public static List<TableGroup> parse(Object document) {
        if (!(document instanceof Map<?, ?> top) || !(top.get("groups") instanceof List<?> entries) || entries.isEmpty()) {
            throw new GroupsFileException("it must start with \"groups:\" followed by at least one group");
        }
        List<TableGroup> groups = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, String> tableOwner = new HashMap<>();
        int index = 0;
        for (Object entry : entries) {
            index++;
            if (!(entry instanceof Map<?, ?> map)) {
                throw new GroupsFileException("group " + index + " is not a block of settings");
            }
            String name = text(map, "name", "group " + index);
            String where = "group " + name;
            checkElement(name, where + ": name");
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new GroupsFileException("two groups are named " + name);
            }
            String section = optional(map, "section", plural(name));
            checkElement(section, where + ": section");
            String schema = optional(map, "schema", null);
            if (!(map.get("root") instanceof Map<?, ?> rootMap)) {
                throw new GroupsFileException(where + " has no root: (its main table and key)");
            }
            TableGroup.Root root = new TableGroup.Root(text(rootMap, "table", where + " root"), text(rootMap, "key", where + " root"));
            checkElement(XmlNames.element(root.key()), where + ": root key");
            List<TableGroup.Link> links = new ArrayList<>();
            Object tables = map.get("tables");
            if (tables != null && !(tables instanceof List<?>)) {
                throw new GroupsFileException(where + ": tables must be a list of related tables");
            }
            Set<String> inGroup = new HashSet<>();
            inGroup.add(root.table().toLowerCase(Locale.ROOT));
            for (Object item : tables == null ? List.of() : (List<?>) tables) {
                if (!(item instanceof Map<?, ?> linkMap)) {
                    throw new GroupsFileException(where + ": every entry under tables needs table, key and references");
                }
                String table = text(linkMap, "table", where + " related table");
                String linkWhere = where + ", table " + table;
                String key = text(linkMap, "key", linkWhere);
                String references = text(linkMap, "references", linkWhere);
                int dot = references.lastIndexOf('.');
                if (dot <= 0 || dot == references.length() - 1) {
                    throw new GroupsFileException(linkWhere + ": references must be written table.column, not \"" + references + "\"");
                }
                String element = optional(linkMap, "element", XmlNames.element(table));
                checkElement(element, linkWhere + ": element");
                String linkSection = optional(linkMap, "section", plural(element));
                checkElement(linkSection, linkWhere + ": section");
                if (!inGroup.add(table.toLowerCase(Locale.ROOT))) {
                    throw new GroupsFileException(where + " lists table " + table + " twice");
                }
                links.add(new TableGroup.Link(table, key, references.substring(0, dot).trim(), references.substring(dot + 1).trim(),
                        element, linkSection, optional(linkMap, "id", null)));
            }
            for (String table : inGroup) {
                String owner = tableOwner.putIfAbsent(table, name);
                if (owner != null) {
                    throw new GroupsFileException("table " + table + " is in groups " + owner + " and " + name
                            + "; a table belongs to one group");
                }
            }
            TableGroup group = new TableGroup(name, section, schema, root, List.copyOf(links));
            checkReferences(group);
            groups.add(group);
        }
        return List.copyOf(groups);
    }

    /** Every foreign key points into the group and leads back to the root, without loops. */
    private static void checkReferences(TableGroup group) {
        for (TableGroup.Link link : group.links()) {
            if (!group.has(link.refTable())) {
                throw new GroupsFileException("group " + group.name() + ", table " + link.table() + ": references "
                        + link.refTable() + "." + link.refColumn() + ", which is not a table of this group");
            }
            Set<String> seen = new HashSet<>();
            String current = link.table();
            while (!group.isRoot(current)) {
                if (!seen.add(current.toLowerCase(Locale.ROOT))) {
                    throw new GroupsFileException("group " + group.name() + ": the references of " + link.table()
                            + " go round in a loop and never reach the root table " + group.root().table());
                }
                current = group.link(current).orElseThrow().refTable();
            }
        }
    }

    private static String text(Map<?, ?> map, String key, String where) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new GroupsFileException(where + " needs " + key + ":");
        }
        return String.valueOf(value).trim();
    }

    private static String optional(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static void checkElement(String value, String where) {
        if (!XmlNames.element(value).equals(value)) {
            throw new GroupsFileException(where + " \"" + value + "\" cannot be an XML name: use letters, digits and _ "
                    + "(for example " + XmlNames.element(value) + ")");
        }
    }

    /** Person → Persons, Address → Addresses, Entity → Entities. */
    static String plural(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch") || lower.endsWith("sh")) {
            return name + "es";
        }
        if (lower.endsWith("y") && lower.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        return name + "s";
    }

    /** A problem in the file, reported with where it is. */
    public static final class GroupsFileException extends RuntimeException {
        GroupsFileException(String message) {
            super(message);
        }
    }
}
