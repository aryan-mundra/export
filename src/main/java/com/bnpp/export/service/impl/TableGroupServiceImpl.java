package com.bnpp.export.service.impl;

import com.bnpp.export.config.TableGroupsConfig;
import com.bnpp.export.dto.GroupTableResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.exception.ServiceUnavailableException;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.ResolvedGroup;
import com.bnpp.export.model.SourceTable;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.repository.SourceTableRepository;
import com.bnpp.export.service.TableGroupService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Implementation of {@link TableGroupService}. */
@Service
public class TableGroupServiceImpl implements TableGroupService {

    private final TableGroupsConfig config;
    private final SourceTableRepository sourceTables;

    public TableGroupServiceImpl(TableGroupsConfig config, SourceTableRepository sourceTables) {
        this.config = config;
        this.sourceTables = sourceTables;
    }

    @Override
    public List<ResolvedGroup> groups() {
        List<ResolvedGroup> resolved = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (TableGroup group : config.groups()) {
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
            throw new ServiceUnavailableException("config/table-groups.yml does not match the database: " + String.join("; ", problems));
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
        return selections;
    }

    private static void requireColumn(Map<String, SourceTable> tables, String table, String column, String what, List<String> problems) {
        SourceTable found = tables.get(table.toLowerCase(Locale.ROOT));
        if (found != null && found.column(column).isEmpty()) {
            problems.add(what + " " + table + "." + column + " was not found (columns: "
                    + String.join(", ", found.columns().stream().map(SourceTable.Column::name).toList()) + ")");
        }
    }
}
