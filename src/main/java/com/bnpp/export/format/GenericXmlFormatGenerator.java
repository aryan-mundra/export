package com.bnpp.export.format;

import com.bnpp.export.model.FormatField;
import com.bnpp.export.service.FormatTemplateService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GenericXmlFormatGenerator implements ExportFormat {

    private final FormatTemplateService formatTemplateService;

    public GenericXmlFormatGenerator(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    @Override
    public String formatType() {
        return "GENERIC_XML";
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
                ? formatTemplateService.activeFields("GENERIC_XML")
                : formatTemplateService.fields(templateId);
        StringBuilder xml = new StringBuilder();
        xml.append(XmlDocuments.header("GenericExport", versionId, subscriberName, records.size()));
        xml.append("  <records>\n");
        for (Map<String, Object> record : records) {
            xml.append("    <record id=\"").append(XmlDocuments.esc(record.get("id"))).append("\">\n");
            if (fields.isEmpty()) {
                for (String field : XmlDocuments.fieldNames()) {
                    if (record.get(field) == null) {
                        continue;
                    }
                    xml.append("      <field name=\"").append(field).append("\">")
                            .append(XmlDocuments.esc(record.get(field)))
                            .append("</field>\n");
                }
            } else {
                for (FormatField field : fields) {
                    Object val = value(record, field.getSourceColumn());
                    if (val == null) {
                        continue;
                    }
                    xml.append("      <field name=\"").append(XmlDocuments.esc(field.getHeaderName())).append("\">")
                            .append(XmlDocuments.esc(val))
                            .append("</field>\n");
                }
            }
            xml.append("    </record>\n");
        }
        xml.append("  </records>\n");
        xml.append("</GenericExport>\n");
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
}
