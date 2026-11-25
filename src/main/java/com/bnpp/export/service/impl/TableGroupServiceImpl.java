package com.bnpp.export.service.impl;

import com.bnpp.export.dto.GroupTableResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.exception.ServiceUnavailableException;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.ResolvedGroup;
import com.bnpp.export.model.SourceTable;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.repository.SourceConstraintRepository;
import com.bnpp.export.repository.SourceTableRepository;
import com.bnpp.export.service.TableGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link TableGroupService}. The groups are not configured: they are discovered from the primary and
 * foreign keys of the source schema ({@code export.source.schema}, default: the connection's current schema) by
 * {@link TableGroupBuilder}, then checked against the tables and columns the database really has. The result is kept
 * for {@code export.source.cache-seconds} so the catalog is not queried on every request.
 */
@Service
public class TableGroupServiceImpl implements TableGroupService {

    private static final Logger log = LoggerFactory.getLogger(TableGroupServiceImpl.class);

    private final SourceConstraintRepository constraints;
    private final SourceTableRepository sourceTables;
    private final String configuredSchema;
    private final long cacheMillis;
    private List<ResolvedGroup> cached;
    private long cachedAt;

    public TableGroupServiceImpl(SourceConstraintRepository constraints, SourceTableRepository sourceTables,
                                 @Value("${export.source.schema:}") String configuredSchema,
                                 @Value("${export.source.cache-seconds:60}") long cacheSeconds) {
        this.constraints = constraints;
        this.sourceTables = sourceTables;
        this.configuredSchema = configuredSchema == null ? "" : configuredSchema.trim();
        this.cacheMillis = Math.max(0, cacheSeconds) * 1000;
    }

    @Override
    public synchronized List<ResolvedGroup> groups() {
        if (cached != null && System.currentTimeMillis() - cachedAt < cacheMillis) {
            return cached;
        }
        String schema = configuredSchema.isEmpty() ? constraints.currentSchema() : configuredSchema;
        List<TableGroup> discovered = TableGroupBuilder.build(constraints.find(schema));
        if (discovered.isEmpty()) {
            throw new ServiceUnavailableException("No table group found in schema " + schema + ": a group is a table with a one-column "
                    + "primary key and no foreign key, with the tables that have a foreign key to it. Check that the schema holds such tables "
                    + "(export.source.schema) and that the foreign keys are declared in the database");
        }
        log.info("Table groups discovered in schema {}: {}", schema, String.join(", ", discovered.stream()
                .map(group -> group.name() + " (" + group.tables().size() + " tables)").toList()));
        cached = resolve(discovered);
        cachedAt = System.currentTimeMillis();
        return cached;
    }

    private List<ResolvedGroup> resolve(List<TableGroup> discovered) {
        List<ResolvedGroup> resolved = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (TableGroup group : discovered) {
            Map<String, SourceTable> tables = new LinkedHashMap<>();
            for (String name : group.tables()) {
                sourceTables.find(group.schema(), name).ifPresentOrElse(
                        table -> tables.put(name.toLowerCase(Locale.ROOT), table),
                        () -> problems.add("group " + group.name() + ": table " + name + " was not found"
                                + (group.schema() == null ? "" : " in schema " + group.schema())));
            }
            requireColumn(tables, group.root().table(), group.root().key(), group.name() + " root key", problems);
            for (TableGroup.Link link : group.links()) {
                requireColumn(tables, link.table(), link.key(), group.name() + " foreign key", problems);
                requireColumn(tables, link.refTable(), link.refColumn(), group.name() + " referenced column", problems);
                if (link.id() != null) {
                    requireColumn(tables, link.table(), link.id(), group.name() + " row id", problems);
                }
            }
            resolved.add(new ResolvedGroup(group, tables));
        }
        if (!problems.isEmpty()) {
            throw new ServiceUnavailableException("The foreign keys of the source schema do not match its tables: " + String.join("; ", problems));
        }
        return resolved;
    }

    @Override
    public List<GroupTableResponse> tables() {
        List<GroupTableResponse> tables = new ArrayList<>();
        for (ResolvedGroup resolved : groups()) {
            TableGroup group = resolved.group();
            for (String name : group.tables()) {
                SourceTable table = resolved.table(name);
                boolean root = group.isRoot(name);
                tables.add(new GroupTableResponse(table.name(), group.name(), root, resolved.column(name, group.joinColumn(name)),
                        resolved.keyColumn(), root ? null : group.joinTarget(name),
                        table.columns().stream().map(column -> new GroupTableResponse.Column(column.name(), column.type(), column.kind())).toList()));
            }
        }
        return tables;
    }

    @Override
    public List<GroupSelection> selections(List<FormatField> fields) {
        List<GroupSelection> selections = new ArrayList<>();
        for (ResolvedGroup resolved : groups()) {
            TableGroup group = resolved.group();
            Map<String, List<FormatField>> byTable = new LinkedHashMap<>();
            for (FormatField field : fields) {
                if (group.has(field.getSourceTable())) {
                    byTable.computeIfAbsent(field.getSourceTable().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(field);
                }
            }
            if (byTable.isEmpty()) {
                continue;
            }
            // The tables with columns and every table between them and the root, in file order (root first).
            Set<String> needed = new LinkedHashSet<>();
            needed.add(group.root().table().toLowerCase(Locale.ROOT));
            for (String table : byTable.keySet()) {
                String current = table;
                while (!group.isRoot(current)) {
                    needed.add(current.toLowerCase(Locale.ROOT));
                    current = group.link(current).orElseThrow().refTable();
                }
            }
            List<String> ordered = group.tables().stream().filter(table -> needed.contains(table.toLowerCase(Locale.ROOT))).toList();
            selections.add(new GroupSelection(resolved, byTable, ordered));
        }
        // The groups come in the order the list's columns first use them (the first column picked decides the first group).
        selections.sort(java.util.Comparator.comparingInt(selection -> firstColumn(selection, fields)));
        return selections;
    }

    /** The place of the first column of the list that belongs to this group. */
    private static int firstColumn(GroupSelection selection, List<FormatField> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (selection.group().has(fields.get(i).getSourceTable())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static void requireColumn(Map<String, SourceTable> tables, String table, String column, String what, List<String> problems) {
        SourceTable found = tables.get(table.toLowerCase(Locale.ROOT));
        if (found != null && found.column(column).isEmpty()) {
            problems.add(what + " " + table + "." + column + " was not found (columns: "
                    + String.join(", ", found.columns().stream().map(SourceTable.Column::name).toList()) + ")");
        }
    }
}
