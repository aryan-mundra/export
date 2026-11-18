package com.bnpp.export.format;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GenericXmlFormatGenerator implements ExportFormat {

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
        StringBuilder xml = new StringBuilder();
        xml.append(XmlDocuments.header("GenericExport", versionId, subscriberName, records.size()));
        xml.append("  <records>\n");
        for (Map<String, Object> record : records) {
            xml.append("    <record id=\"").append(XmlDocuments.esc(record.get("id"))).append("\">\n");
            for (String field : XmlDocuments.fieldNames()) {
                if (record.get(field) == null) {
                    continue;
                }
                xml.append("      <field name=\"").append(field).append("\">")
                        .append(XmlDocuments.esc(record.get(field)))
                        .append("</field>\n");
            }
            xml.append("    </record>\n");
        }
        xml.append("  </records>\n");
        xml.append("</GenericExport>\n");
        return XmlDocuments.bytes(xml.toString());
    }
}
