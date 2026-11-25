package com.bnpp.export.service.impl;

import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.GroupPreviewResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.model.GroupRow;
import com.bnpp.export.model.GroupSelection;
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
        try {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.write("<Export list=\"" + XmlNames.escape(listName) + "\"" + (listVersion == null ? "" : " version=\"" + listVersion + "\"")
                    + " generatedAt=\"" + LocalDateTime.now().withNano(0) + "\">\n");
            long total = 0;
            for (GroupSelection selection : tableGroups.selections(fields)) {
                TableGroup group = selection.group();
                String key = selection.resolved().keyColumn();
                String attribute = XmlNames.element(group.root().key());
                out.write("  <" + group.section() + " count=\"" + records.count(selection) + "\">\n");
                long[] written = new long[1];
                records.stream(selection, record -> {
                    StringBuilder xml = new StringBuilder(512);
                    xml.append("    <").append(group.name()).append(' ').append(attribute).append("=\"")
                            .append(XmlNames.escape(text(record.get(key)))).append("\">\n");
                    writeValues(selection, group.root().table(), record, xml, "      ");
                    writeChildren(selection, group.root().table(), record, xml, "      ");
                    xml.append("    </").append(group.name()).append(">\n");
                    write(out, xml);
                    written[0]++;
                });
                out.write("  </" + group.section() + ">\n");
                total += written[0];
            }
            out.write("</Export>\n");
            return total;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the XML file: " + ex.getMessage(), ex);
        }
    }

    private static void writeValues(GroupSelection selection, String table, GroupRow row, StringBuilder xml, String indent) {
        for (FormatField field : selection.valueFields(table)) {
            String value = text(row.get(selection.column(table, field.getSourceColumn())));
            if (!value.isEmpty()) {
                String element = XmlNames.element(field.getHeaderName());
                xml.append(indent).append('<').append(element).append('>').append(XmlNames.escape(value))
                        .append("</").append(element).append(">\n");
            }
        }
    }

    private static void writeChildren(GroupSelection selection, String table, GroupRow row, StringBuilder xml, String indent) {
        for (TableGroup.Link link : selection.group().childrenOf(table)) {
            List<GroupRow> children = row.children(link.table());
            if (children.isEmpty()) {
                continue;
            }
            xml.append(indent).append('<').append(link.section()).append(">\n");
            for (GroupRow child : children) {
                xml.append(indent).append("  <").append(link.element()).append(">\n");
                writeValues(selection, link.table(), child, xml, indent + "    ");
                writeChildren(selection, link.table(), child, xml, indent + "    ");
                xml.append(indent).append("  </").append(link.element()).append(">\n");
            }
            xml.append(indent).append("</").append(link.section()).append(">\n");
        }
    }

    private static void write(Writer out, CharSequence text) {
        try {
            out.append(text);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
