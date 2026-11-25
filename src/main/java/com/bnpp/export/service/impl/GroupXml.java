package com.bnpp.export.service.impl;

import com.bnpp.export.entity.FormatField;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.model.VersionLayout;
import com.bnpp.export.util.XmlNames;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one place that decides what the XML looks like. Both the live file of a list and the file sent to a subscriber
 * (a stored data version) are written here, so they can never drift apart. See docs/xml-format.md.
 *
 * <pre>{@code
 * <Export list="Sanctions Screening" listVersion="1" dataVersion="2" generatedAt="2026-11-25T10:00:00" records="3">
 *   <persons count="2">
 *     <person person_id="101">
 *       <birth_year>1970</birth_year>
 *       <address address_no="11">
 *         <city>London</city>
 *         <country id="1"><code>GB</code></country>
 *       </address>
 *       <name name_id="1"><first_name>John</first_name></name>
 *     </person>
 *   </persons>
 * </Export>
 * }</pre>
 *
 * <ul>
 *   <li>One section per group, in the order the list's columns first use the groups; one element per record, its key as
 *       an attribute named after the key column.</li>
 *   <li>A record's own columns come first, in the list's order, then its related rows.</li>
 *   <li>A related row is one element named after its table, repeated for every row (no wrapper element). When the table
 *       has a one-column primary key, it is written as an attribute so the row can be referred to.</li>
 *   <li>Empty values are left out. A related element that would have the same name as a column of its parent gets the
 *       ending {@code _row}.</li>
 * </ul>
 */
final class GroupXml {

    private static final String INDENT = "  ";

    private GroupXml() {
    }

    /** What a list's groups, tables and columns look like in a file; stored with every data version. */
    static VersionLayout layout(List<GroupSelection> selections) {
        List<VersionLayout.Group> groups = new ArrayList<>();
        for (GroupSelection selection : selections) {
            TableGroup group = selection.group();
            List<VersionLayout.Table> tables = new ArrayList<>();
            for (String table : selection.tables()) {
                List<String> headers = selection.valueFields(table).stream().map(FormatField::getHeaderName).toList();
                TableGroup.Link link = group.link(table).orElse(null);
                tables.add(link == null
                        ? new VersionLayout.Table(table, null, group.name(), group.section(), false, headers, null)
                        : new VersionLayout.Table(table, link.refTable(), link.element(), link.section(), link.id() != null, headers, link.id()));
            }
            groups.add(new VersionLayout.Group(group.name(), group.section(), selection.resolved().keyColumn(),
                    XmlNames.element(group.root().key()), group.root().table(), tables,
                    selection.fields().stream().filter(field -> field.getFilterValues() != null && !field.getFilterValues().isEmpty())
                            .map(field -> new VersionLayout.Filter(field.getSourceTable(), field.getSourceColumn(), field.getFilterValues())).toList()));
        }
        return new VersionLayout(groups);
    }

    /** The opening of the file; {@code dataVersion} is null for the live file of a list. */
    static void open(Writer out, String list, Integer listVersion, Integer dataVersion, String generatedAt, long records) throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<Export list=\"" + XmlNames.escape(list) + "\""
                + (listVersion == null ? "" : " listVersion=\"" + listVersion + "\"")
                + (dataVersion == null ? "" : " dataVersion=\"" + dataVersion + "\"")
                + " generatedAt=\"" + generatedAt + "\" records=\"" + records + "\">\n");
    }

    static void close(Writer out) throws IOException {
        out.write("</Export>\n");
    }

    static void openGroup(Writer out, VersionLayout.Group group, long count) throws IOException {
        out.write(INDENT + "<" + group.section() + " count=\"" + count + "\">\n");
    }

    static void closeGroup(Writer out, VersionLayout.Group group) throws IOException {
        out.write(INDENT + "</" + group.section() + ">\n");
    }

    /** One record of a group, as indented text ending with a newline. */
    static String record(VersionLayout.Group group, String key, Map<String, Object> doc) {
        StringBuilder xml = new StringBuilder(512);
        xml.append(INDENT).append(INDENT).append('<').append(group.name()).append(' ').append(group.keyAttribute()).append("=\"")
                .append(XmlNames.escape(key)).append("\">\n");
        node(group, group.rootTable(), doc, xml, INDENT.repeat(3));
        xml.append(INDENT).append(INDENT).append("</").append(group.name()).append(">\n");
        return xml.toString();
    }

    private static void node(VersionLayout.Group group, String table, Map<String, Object> node, StringBuilder xml, String indent) {
        VersionLayout.Table layout = group.table(table);
        Map<String, Object> values = RecordDocuments.values(node);
        Set<String> columnNames = new HashSet<>();
        for (String header : layout.headers()) {
            String element = XmlNames.element(header);
            columnNames.add(element.toLowerCase(Locale.ROOT));
            Object value = values.get(header);
            if (value != null) {
                xml.append(indent).append('<').append(element).append('>').append(XmlNames.escape(value)).append("</").append(element).append(">\n");
            }
        }
        for (VersionLayout.Table child : group.childrenOf(table)) {
            String element = columnNames.contains(child.element().toLowerCase(Locale.ROOT)) ? child.element() + "_row" : child.element();
            String idAttribute = XmlNames.element(child.idColumn() == null ? "id" : child.idColumn());
            for (Map<String, Object> row : RecordDocuments.rows(node, child.table())) {
                Object id = row.get(RecordDocuments.ID);
                xml.append(indent).append('<').append(element);
                if (id != null) {
                    xml.append(' ').append(idAttribute).append("=\"").append(XmlNames.escape(id)).append('"');
                }
                xml.append(">\n");
                node(group, child.table(), row, xml, indent + INDENT);
                xml.append(indent).append("</").append(element).append(">\n");
            }
        }
    }

    /** Writes a group's records through {@code writer}; used so callers can stream without knowing the format. */
    static void write(Writer out, CharSequence text) {
        try {
            out.append(text);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
