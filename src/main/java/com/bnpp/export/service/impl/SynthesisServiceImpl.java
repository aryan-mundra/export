package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.SynthesisRecordPageResponse;
import com.bnpp.export.dto.SynthesisRecordResponse;
import com.bnpp.export.dto.SynthesisRecordResponse.Difference;
import com.bnpp.export.dto.SynthesisSummaryResponse;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.model.DataVersion;
import com.bnpp.export.model.RecordChange;
import com.bnpp.export.model.VersionLayout;
import com.bnpp.export.repository.DataVersionRepository;
import com.bnpp.export.service.DataVersionService;
import com.bnpp.export.service.SynthesisService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Implementation of {@link SynthesisService}.
 *
 * <p>Records are matched on (group, key). Added, removed, changed and unchanged records are counted in SQL (equal
 * hashes mean equal records, so unchanged records are never read). What changed inside a record comes from comparing
 * its two stored documents: its own values, then each related table row by row, in the order of the groups file.
 * Rows are matched on their id when the groups file gives one (so a row can be "changed"); otherwise identical rows
 * match and the others are "removed" and "added".
 */
@Service
public class SynthesisServiceImpl implements SynthesisService {

    private static final int PAGE = 500;
    private static final String VALUE_CHANGED = "VALUE_CHANGED";
    private static final String ROW_ADDED = "ROW_ADDED";
    private static final String ROW_REMOVED = "ROW_REMOVED";

    private final DataVersionService dataVersions;
    private final DataVersionRepository repository;

    public SynthesisServiceImpl(DataVersionService dataVersions, DataVersionRepository repository) {
        this.dataVersions = dataVersions;
        this.repository = repository;
    }

    // ------------------------------------------------------------------ summary

    @Override
    public SynthesisSummaryResponse summary(Long afterId, Long beforeId) {
        DataVersion after = dataVersions.get(afterId);
        DataVersion before = dataVersions.get(beforeId);
        if (!after.familyId().equals(before.familyId())) {
            throw new BadRequestException("Choose two versions of the same list");
        }
        VersionLayout afterLayout = dataVersions.layout(afterId);
        VersionLayout beforeLayout = dataVersions.layout(beforeId);
        Map<String, long[]> counts = repository.countChanges(afterId, beforeId);
        Set<String> order = new LinkedHashSet<>();
        afterLayout.groups().forEach(group -> order.add(group.name()));
        beforeLayout.groups().forEach(group -> order.add(group.name()));
        List<SynthesisSummaryResponse.Group> groups = new ArrayList<>();
        for (String name : order) {
            long[] c = counts.getOrDefault(name, new long[4]);
            VersionLayout.Group layout = afterLayout.group(name) != null ? afterLayout.group(name) : beforeLayout.group(name);
            Tally tally = new Tally();
            String last = null;
            List<String[]> page;
            do {
                page = repository.changedPage(afterId, beforeId, name, last, PAGE);
                for (String[] row : page) {
                    tally.add(diff(layout, RecordDocuments.read(row[2]), RecordDocuments.read(row[1])));
                    last = row[0];
                }
            } while (page.size() == PAGE);
            Set<String> afterColumns = columnsOf(afterLayout.group(name));
            Set<String> beforeColumns = columnsOf(beforeLayout.group(name));
            groups.add(new SynthesisSummaryResponse.Group(name, before.groups().getOrDefault(name, 0L), after.groups().getOrDefault(name, 0L),
                    c[0], c[1], c[2], c[3], tally.tables(layout), tally.columns(),
                    afterColumns.stream().filter(column -> !beforeColumns.contains(column)).toList(),
                    beforeColumns.stream().filter(column -> !afterColumns.contains(column)).toList()));
        }
        return new SynthesisSummaryResponse(DataVersionResponse.from(before), DataVersionResponse.from(after), groups);
    }

    /** Counts per table and per column over a group's changed records. */
    private static final class Tally {
        private final Map<String, long[]> tables = new LinkedHashMap<>();
        private final Map<String, Long> columns = new LinkedHashMap<>();

        void add(List<Difference> differences) {
            Set<String> touched = new LinkedHashSet<>();
            for (Difference difference : differences) {
                String table = difference.table().toLowerCase(Locale.ROOT);
                long[] t = tables.computeIfAbsent(table, ignored -> new long[4]);
                touched.add(table);
                switch (difference.kind()) {
                    case ROW_ADDED -> t[1]++;
                    case ROW_REMOVED -> t[2]++;
                    default -> {
                        t[3]++;
                        columns.merge(difference.table() + '\u0000' + difference.column(), 1L, Long::sum);
                    }
                }
            }
            touched.forEach(table -> tables.get(table)[0]++);
        }

        List<SynthesisSummaryResponse.Table> tables(VersionLayout.Group layout) {
            return layout.tables().stream().map(table -> {
                long[] t = tables.getOrDefault(table.table().toLowerCase(Locale.ROOT), new long[4]);
                boolean root = table.parent() == null;
                return new SynthesisSummaryResponse.Table(table.table(), root ? table.element() : table.section(), root, t[0], t[1], t[2], t[3]);
            }).toList();
        }

        List<SynthesisSummaryResponse.Column> columns() {
            List<SynthesisSummaryResponse.Column> result = new ArrayList<>();
            columns.forEach((key, count) -> {
                String[] parts = key.split("\u0000", 2);
                result.add(new SynthesisSummaryResponse.Column(parts[0], parts[1], count));
            });
            result.sort((a, b) -> Long.compare(b.changed(), a.changed()));
            return result;
        }
    }

    private static Set<String> columnsOf(VersionLayout.Group group) {
        Set<String> columns = new TreeSet<>();
        if (group != null) {
            group.tables().forEach(table -> table.headers().forEach(header -> columns.add(table.table() + "." + header)));
        }
        return columns;
    }

    // ------------------------------------------------------------------ records

    @Override
    public SynthesisRecordPageResponse records(Long afterId, Long beforeId, String group, RecordChange.Filter filter, String query,
                                               int offset, int limit) {
        VersionLayout.Group layout = layoutOf(afterId, beforeId, group);
        int start = Math.max(0, offset);
        Map.Entry<Long, List<DataVersionRepository.Compared>> page = repository.compared(afterId, beforeId, group,
                filter == null ? RecordChange.Filter.CHANGES : filter, query, start, Math.min(100, Math.max(1, limit)));
        List<SynthesisRecordPageResponse.Row> rows = page.getValue().stream().map(record -> {
            RecordChange change = record.change();
            String summary = switch (change) {
                case ADDED -> "New in the newer version";
                case REMOVED -> "Not in the newer version";
                case UNCHANGED -> "";
                case CHANGED -> describe(layout, diff(layout, RecordDocuments.read(record.beforeDoc()), RecordDocuments.read(record.afterDoc())));
            };
            return new SynthesisRecordPageResponse.Row(record.key(), change.name(), summary);
        }).toList();
        return new SynthesisRecordPageResponse(page.getKey(), start, rows);
    }

    @Override
    public SynthesisRecordResponse record(Long afterId, Long beforeId, String group, String key) {
        VersionLayout.Group layout = layoutOf(afterId, beforeId, group);
        Map<String, Object> after = repository.doc(afterId, group, key).map(RecordDocuments::read).orElse(null);
        Map<String, Object> before = repository.doc(beforeId, group, key).map(RecordDocuments::read).orElse(null);
        if (after == null && before == null) {
            throw new ResourceNotFoundException("Record " + key + " is in neither version");
        }
        RecordChange change = before == null ? RecordChange.ADDED : after == null ? RecordChange.REMOVED
                : Objects.equals(before, after) ? RecordChange.UNCHANGED : RecordChange.CHANGED;
        return new SynthesisRecordResponse(group, key, change.name(), before, after, diff(layout, before, after));
    }

    private VersionLayout.Group layoutOf(Long afterId, Long beforeId, String group) {
        VersionLayout.Group layout = dataVersions.layout(afterId).group(group);
        if (layout == null) {
            layout = dataVersions.layout(beforeId).group(group);
        }
        if (layout == null) {
            throw new BadRequestException("Group " + group + " is in neither version");
        }
        return layout;
    }

    // ------------------------------------------------------------------ comparing two records

    /** Every difference between two versions of a record (either may be null: the whole record was added or removed). */
    static List<Difference> diff(VersionLayout.Group layout, Map<String, Object> before, Map<String, Object> after) {
        List<Difference> differences = new ArrayList<>();
        diffNode(layout, layout.rootTable(), "", before == null ? Map.of() : before, after == null ? Map.of() : after, differences);
        return differences;
    }

    private static void diffNode(VersionLayout.Group layout, String table, String path, Map<String, Object> before,
                                 Map<String, Object> after, List<Difference> differences) {
        Map<String, Object> was = RecordDocuments.values(before);
        Map<String, Object> now = RecordDocuments.values(after);
        Set<String> columns = new TreeSet<>(was.keySet());
        columns.addAll(now.keySet());
        for (String column : columns) {
            if (!Objects.equals(was.get(column), now.get(column))) {
                differences.add(new Difference(path, table, VALUE_CHANGED, column, text(was.get(column)), text(now.get(column))));
            }
        }
        // Related tables in the order of the groups file, then any the layout no longer knows.
        Set<String> present = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        present.addAll(RecordDocuments.tables(before).keySet());
        present.addAll(RecordDocuments.tables(after).keySet());
        List<String> tables = new ArrayList<>();
        for (VersionLayout.Table child : layout.childrenOf(table)) {
            if (present.remove(child.table())) {
                tables.add(child.table());
            }
        }
        tables.addAll(present);
        for (String child : tables) {
            diffRows(layout, child, path, RecordDocuments.rows(before, child), RecordDocuments.rows(after, child), differences);
        }
    }

    private static void diffRows(VersionLayout.Group layout, String table, String path, List<Map<String, Object>> beforeRows,
                                 List<Map<String, Object>> afterRows, List<Difference> differences) {
        List<Map<String, Object>> was = new ArrayList<>(beforeRows);
        List<Map<String, Object>> now = new ArrayList<>(afterRows);
        VersionLayout.Table childLayout = layout.table(table);
        String label = childLayout == null ? table : childLayout.element();
        // Rows with an id are matched on it: same id, different content = changed.
        for (Map<String, Object> row : new ArrayList<>(now)) {
            Object id = row.get(RecordDocuments.ID);
            if (id == null) {
                continue;
            }
            Map<String, Object> match = was.stream().filter(other -> id.equals(other.get(RecordDocuments.ID))).findFirst().orElse(null);
            if (match != null) {
                was.remove(match);
                now.remove(row);
                if (!match.equals(row)) {
                    diffNode(layout, table, join(path, label + " " + id), match, row, differences);
                }
            }
        }
        // Identical rows match; the others were removed or added.
        now.removeIf(was::remove);
        was.forEach(row -> differences.add(new Difference(join(path, label), table, ROW_REMOVED, null, describeRow(row), null)));
        now.forEach(row -> differences.add(new Difference(join(path, label), table, ROW_ADDED, null, null, describeRow(row))));
    }

    /** "birth_year changed · Names: 1 removed · Addresses: 1 value changed". */
    static String describe(VersionLayout.Group layout, List<Difference> differences) {
        Map<String, int[]> byTable = new LinkedHashMap<>();
        List<String> rootColumns = new ArrayList<>();
        for (Difference difference : differences) {
            boolean root = difference.table().equalsIgnoreCase(layout.rootTable());
            if (root && VALUE_CHANGED.equals(difference.kind())) {
                rootColumns.add(difference.column());
                continue;
            }
            VersionLayout.Table table = layout.table(difference.table());
            int[] c = byTable.computeIfAbsent(table == null ? difference.table() : table.section(), ignored -> new int[3]);
            switch (difference.kind()) {
                case ROW_ADDED -> c[0]++;
                case ROW_REMOVED -> c[1]++;
                default -> c[2]++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (!rootColumns.isEmpty()) {
            parts.add(String.join(", ", rootColumns) + " changed");
        }
        byTable.forEach((label, c) -> {
            List<String> bits = new ArrayList<>();
            if (c[0] > 0) {
                bits.add(c[0] + " added");
            }
            if (c[1] > 0) {
                bits.add(c[1] + " removed");
            }
            if (c[2] > 0) {
                bits.add(c[2] + " value" + (c[2] == 1 ? "" : "s") + " changed");
            }
            parts.add(label + ": " + String.join(", ", bits));
        });
        return String.join(" · ", parts);
    }

    private static String describeRow(Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        if (row.get(RecordDocuments.ID) != null) {
            parts.add("id " + row.get(RecordDocuments.ID));
        }
        RecordDocuments.values(row).forEach((column, value) -> parts.add(column + "=" + value));
        String text = String.join(", ", parts);
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static String join(String path, String part) {
        return path.isEmpty() ? part : path + " › " + part;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
