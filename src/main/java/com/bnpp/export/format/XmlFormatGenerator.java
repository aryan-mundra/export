package com.bnpp.export.format;

import com.bnpp.export.entity.FormatField;
import com.bnpp.export.service.FormatTemplateService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * XML file: one {@code <Record>} per list record and one element per template column,
 * named exactly as the column is named on the template.
 *
 * Streaming: the root element carries recordCount, which is only known at the end, so records are
 * streamed into a body file first and the finished file is assembled on disk as
 * header + body + footer. Memory use does not depend on the number of records.
 */
@Component
public class XmlFormatGenerator implements ExportFormat {

    public static final String FORMAT = "XML";

    private final FormatTemplateService formatTemplateService;

    public XmlFormatGenerator(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    @Override
    public String formatType() {
        return FORMAT;
    }

    @Override
    public String fileExtension() {
        return "xml";
    }

    @Override
    public RecordWriter open(Path target, Long versionId, String subscriberName, Long templateId) throws IOException {
        List<FormatField> fields = formatTemplateService.fields(templateId);
        if (fields.isEmpty()) {
            throw new IllegalStateException("No approved list for this subscriber. Choose one on the Subscribers page.");
        }
        LocalDateTime generated = LocalDateTime.now();
        Path body = target.resolveSibling(target.getFileName() + ".body");
        Writer out = RecordValues.utf8Writer(body);
        return new RecordWriter() {
            private long count;
            private boolean bodyClosed;

            @Override
            public void write(Map<String, Object> record) throws IOException {
                StringBuilder xml = new StringBuilder(256);
                xml.append("  <Record id=\"").append(XmlDocuments.esc(record.get("id"))).append("\">\n");
                for (FormatField field : fields) {
                    Object value = RecordValues.value(record, field);
                    if (value == null) {
                        continue;
                    }
                    String tag = elementName(field);
                    xml.append("    <").append(tag).append('>')
                            .append(XmlDocuments.esc(value))
                            .append("</").append(tag).append(">\n");
                }
                xml.append("  </Record>\n");
                out.write(xml.toString());
                count++;
            }

            @Override
            public long count() {
                return count;
            }

            @Override
            public void finish() throws IOException {
                closeBody();
                try (OutputStream file = Files.newOutputStream(target)) {
                    file.write(XmlDocuments.header("Export", versionId, subscriberName, count, generated)
                            .getBytes(StandardCharsets.UTF_8));
                    Files.copy(body, file);
                    file.write("</Export>\n".getBytes(StandardCharsets.UTF_8));
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    closeBody();
                } finally {
                    Files.deleteIfExists(body);
                }
            }

            private void closeBody() throws IOException {
                if (!bodyClosed) {
                    bodyClosed = true;
                    out.close();
                }
            }
        };
    }

    private String elementName(FormatField field) {
        String name = elementName(field.getHeaderName());
        return name.equals("Field") && field.getSourceColumn() != null ? elementName(field.getSourceColumn()) : name;
    }

    /**
     * The XML element for a column name. A list has no format of its own, so its column names are free text
     * (spaces and all, which CSV keeps as they are); in XML each run of characters an element cannot hold
     * becomes "_", e.g. "Country risk" is written as Country_risk.
     */
    public static String elementName(String header) {
        String value = (header == null ? "" : header.trim()).replaceAll("[^A-Za-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
        if (value.isEmpty()) {
            return "Field";
        }
        if (!Character.isLetter(value.charAt(0)) && value.charAt(0) != '_') {
            value = "_" + value;
        }
        return value.toLowerCase(java.util.Locale.ROOT).startsWith("xml") ? "_" + value : value;
    }
}
