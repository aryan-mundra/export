package com.bnpp.export.service.impl;

import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.model.SourceTable;
import com.bnpp.export.model.ResolvedGroup;
import com.bnpp.export.dto.ColumnValuesResponse;
import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.GroupPreviewResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.model.GroupRow;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.VersionLayout;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.bnpp.export.repository.GroupRecordRepository;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.GroupExportService;
import com.bnpp.export.service.TableGroupService;
import com.bnpp.export.util.XmlNames;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link GroupExportService}.
 *
 * <p>XML layout: the groups in the order of their root table names, each in its section element; one element per record
 * carrying the group's key as an attribute named after the key column; the list's columns of the root table as
 * child elements; each related table as a section of row elements, nested as the foreign keys go. Empty values are
 * left out. Records are written as they are read, so memory use does not grow with the file:
 *
 * <pre>{@code
 * <Export list="Sanctions" version="3" generatedAt="2026-11-25T10:00:00">
 *   <Persons count="2">
 *     <Person person_id="101">
 *       <first_name>JOHN</first_name>
 *       <Addresses>
 *         <Address><city>London</city></Address>
 *       </Addresses>
 *     </Person>
 *   </Persons>
 * </Export>
 * }</pre>
 */
@Service
public class GroupExportServiceImpl implements GroupExportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TableGroupService tableGroups;
    private final GroupRecordRepository records;
    private final FormatTemplateService templates;
    private final FormatTemplateRepository templateRepository;
    private final AuditService audit;

    public GroupExportServiceImpl(TableGroupService tableGroups, GroupRecordRepository records, FormatTemplateService templates,
                                  FormatTemplateRepository templateRepository, AuditService audit) {
        this.tableGroups = tableGroups;
        this.records = records;
        this.templates = templates;
        this.templateRepository = templateRepository;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ preview

    @Override
    public List<GroupPreviewResponse> preview(List<FormatFieldRequest> fields, int offset, int limit) {
        List<GroupPreviewResponse> previews = new ArrayList<>();
        int start = Math.max(0, offset);
        int size = Math.min(100, Math.max(1, limit));
        for (GroupSelection selection : tableGroups.selections(templates.previewFields(fields))) {
            TableGroup group = selection.group();
            String key = selection.resolved().keyColumn();
            List<FormatField> columns = selection.fields().stream().filter(field -> !selection.isKey(field)).toList();
            List<GroupPreviewResponse.Row> rows = new ArrayList<>();
            for (GroupRow record : records.page(selection, start, size)) {
                rows.add(new GroupPreviewResponse.Row(text(record.get(key)),
                        columns.stream().map(field -> flatValue(selection, record, field)).toList()));
            }
            previews.add(new GroupPreviewResponse(group.name(), group.root().table(), key,
                    columns.stream().map(field -> new GroupPreviewResponse.Column(field.getSourceTable(), field.getSourceColumn(),
                            field.getHeaderName(), group.isRoot(field.getSourceTable()))).toList(),
                    records.count(selection), start, rows));
        }
        return previews;
    }

    @Override
    public ColumnValuesResponse columnValues(String table, String column, String contains) {
        String wantedTable = table == null ? "" : table.trim();
        ResolvedGroup resolved = tableGroups.groups().stream().filter(candidate -> candidate.group().has(wantedTable)).findFirst()
                .orElseThrow(() -> new BadRequestException("Table " + wantedTable + " is not in any table group of the source schema"));
        SourceTable source = resolved.table(wantedTable);
        SourceTable.Column found = source.column(column == null ? "" : column.trim())
                .orElseThrow(() -> new BadRequestException("Unknown column " + column + " on " + source.name()));
        List<String> values = records.distinctValues(source, found.name(), contains, MAX_VALUES + 1);
        return new ColumnValuesResponse(values.size() > MAX_VALUES ? values.subList(0, MAX_VALUES) : values, values.size() > MAX_VALUES);
    }

    /** A column's value for one record: the root's own value, or every value of the related rows, "; "-separated. */
    private static String flatValue(GroupSelection selection, GroupRow record, FormatField field) {
        String column = selection.column(field.getSourceTable(), field.getSourceColumn());
        if (selection.group().isRoot(field.getSourceTable())) {
            return text(record.get(column));
        }
        Set<String> found = new LinkedHashSet<>();
        collect(record, field.getSourceTable(), column, found);
        return String.join("; ", found);
    }

    private static void collect(GroupRow row, String table, String column, Set<String> found) {
        row.children().forEach((childTable, children) -> {
            for (GroupRow child : children) {
                if (childTable.equalsIgnoreCase(table)) {
                    String value = text(child.get(column));
                    if (!value.isEmpty()) {
                        found.add(value);
                    }
                }
                collect(child, table, column, found);
            }
        });
    }

    // ------------------------------------------------------------------ XML

    @Override
    public FileDownload xml(Long templateId) {
        FormatTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("List version not found"));
        List<FormatField> fields = templates.fields(templateId);
        if (fields.isEmpty()) {
            throw new BadRequestException("This list version has no columns");
        }
        try {
            Path file = Files.createTempFile("regliss-list", ".xml");
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writeXml(template.getName(), template.getVersionNumber(), fields, out);
            } catch (IOException | RuntimeException ex) {
                Files.deleteIfExists(file);
                throw ex;
            }
            audit.template(template.getId(), "LIST_XML_DOWNLOADED",
                    "\"" + template.getName() + "\" v" + template.getVersionNumber() + " downloaded as XML");
            return FileDownload.temporary(XmlNames.element(template.getName()) + "_v" + template.getVersionNumber() + "_"
                    + LocalDateTime.now().format(STAMP) + ".xml", file);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the XML file", ex);
        }
    }

    @Override
    public long writeXml(String listName, Integer listVersion, List<FormatField> fields, Writer out) {
        List<GroupSelection> selections = tableGroups.selections(fields);
        VersionLayout layout = GroupXml.layout(selections);
        try {
            long[] counts = new long[selections.size()];
            long expected = 0;
            for (int i = 0; i < counts.length; i++) {
                counts[i] = records.count(selections.get(i));
                expected += counts[i];
            }
            GroupXml.open(out, listName, listVersion, null, LocalDateTime.now().withNano(0).toString(), expected);
            long total = 0;
            for (int i = 0; i < selections.size(); i++) {
                GroupSelection selection = selections.get(i);
                VersionLayout.Group group = layout.groups().get(i);
                String key = selection.resolved().keyColumn();
                GroupXml.openGroup(out, group, counts[i]);
                long[] written = new long[1];
                records.stream(selection, record -> {
                    // The same canonical document a data version stores, so the live file and a sent file read alike.
                    Map<String, Object> doc = RecordDocuments.build(selection, selection.group().root().table(), record);
                    GroupXml.write(out, GroupXml.record(group, text(record.get(key)), doc));
                    written[0]++;
                });
                GroupXml.closeGroup(out, group);
                total += written[0];
            }
            GroupXml.close(out);
            return total;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the XML file: " + ex.getMessage(), ex);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
