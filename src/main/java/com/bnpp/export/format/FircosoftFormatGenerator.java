package com.bnpp.export.format;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FircosoftFormatGenerator implements ExportFormat {

    @Override
    public String formatType() {
        return "FIRCOSOFT";
    }

    @Override
    public String fileExtension() {
        return "xml";
    }

    @Override
    public byte[] generate(Long versionId, String subscriberName, List<Map<String, Object>> records) {
        StringBuilder xml = new StringBuilder();
        xml.append(XmlDocuments.header("FircosoftList", versionId, subscriberName, records.size()));
        for (Map<String, Object> record : records) {
            xml.append("  <Entity id=\"").append(XmlDocuments.esc(record.get("id"))).append("\">\n");
            XmlDocuments.appendFields(xml, record, "    ");
            xml.append("  </Entity>\n");
        }
        xml.append("</FircosoftList>\n");
        return XmlDocuments.bytes(xml.toString());
    }
}
