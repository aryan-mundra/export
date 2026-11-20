package com.bnpp.export.format;

import com.bnpp.export.model.FormatField;
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
import java.util.regex.Pattern;

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

    /** A name that can be used as an XML element: starts with a letter or _, then letters, digits, _ . or -. */
    public static final Pattern ELEMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final FormatTemplateService formatTemplateService;

    public XmlFormatGenerator(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    /** Returns null when the name works as an XML element, otherwise the reason it does not. */
    public static String elementNameProblem(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            return "Header name is required";
        }
        if (!ELEMENT_NAME.matcher(value).matches()) {
            return "\"" + value + "\" cannot be an XML element name. Start with a letter or _, and use only letters, digits, _ . or - (no spaces).";
        }
        if (value.toLowerCase().startsWith("xml")) {
            return "\"" + value + "\" cannot be an XML element name. Names may not start with xml.";
        }
        return null;
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
            throw new IllegalStateException("No approved XML template for this subscriber. Choose one on the Subscribers page.");
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

    /** Template names are checked when saved; anything older that does not fit falls back to the column name. */
    private String elementName(FormatField field) {
        String header = field.getHeaderName() == null ? "" : field.getHeaderName().trim();
        if (elementNameProblem(header) == null) {
            return header;
        }
        String fallback = field.getSourceColumn().replaceAll("[^A-Za-z0-9_.-]", "");
        return fallback.isEmpty() || !Character.isLetter(fallback.charAt(0)) ? "Field" : fallback;
    }
}
