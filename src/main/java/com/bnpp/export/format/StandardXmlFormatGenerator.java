package com.bnpp.export.format;

import com.bnpp.export.model.FormatField;
import com.bnpp.export.service.FormatTemplateService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StandardXmlFormatGenerator implements ExportFormat {

    private final FormatTemplateService formatTemplateService;

    public StandardXmlFormatGenerator(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    @Override
    public String formatType() {
        return "STANDARD_XML";
    }

    @Override
    public String fileExtension() {
        return "xml";
    }

    @Override
    public byte[] generate(Long versionId, String subscriberName, List<Map<String, Object>> records) {
        return generate(versionId, subscriberName, records, null);
    }

    public byte[] generate(Long versionId, String subscriberName, List<Map<String, Object>> records, Long templateId) {
        List<FormatField> fields = templateId == null
                ? formatTemplateService.activeFields("STANDARD_XML")
                : formatTemplateService.fields(templateId);
        StringBuilder xml = new StringBuilder();
        xml.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <StandardExport>
                  <Header>
                    <VersionId>%s</VersionId>
                    <Subscriber>%s</Subscriber>
                    <RecordCount>%d</RecordCount>
                  </Header>
                  <Body>
                """.formatted(versionId, XmlDocuments.esc(subscriberName), records.size()));
        for (Map<String, Object> record : records) {
            xml.append("    <Record id=\"").append(XmlDocuments.esc(record.get("id"))).append("\">\n");
            if (fields.isEmpty()) {
                XmlDocuments.appendFields(xml, record, "      ");
            } else {
                for (FormatField field : fields) {
                    Object val = value(record, field.getSourceColumn());
                    if (val == null) {
                        continue;
                    }
                    String tag = sanitizeTag(field.getHeaderName(), field.getSourceColumn());
                    xml.append("      <").append(tag).append('>')
                            .append(XmlDocuments.esc(val))
                            .append("</").append(tag).append(">\n");
                }
            }
            xml.append("    </Record>\n");
        }
        xml.append("""
                  </Body>
                </StandardExport>
                """);
        return XmlDocuments.bytes(xml.toString());
    }

    private Object value(Map<String, Object> record, String column) {
        if (record.containsKey(column) && record.get(column) != null) {
            return record.get(column);
        }
        if ("record_date".equals(column)) {
            return record.get("dateOfBirth");
        }
        return record.get(column);
    }

    private String sanitizeTag(String headerName, String fallback) {
        String candidate = (headerName == null ? "" : headerName.trim()).replaceAll("[^A-Za-z0-9_.-]", "");
        if (candidate.isEmpty() || !Character.isLetter(candidate.charAt(0))) {
            candidate = fallback == null || fallback.isEmpty() ? "Field" : fallback;
        }
        return Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1);
    }
}
