package com.bnpp.export.format;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StandardXmlFormatGenerator implements ExportFormat {

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
            XmlDocuments.appendFields(xml, record, "      ");
            xml.append("    </Record>\n");
        }
        xml.append("""
                  </Body>
                </StandardExport>
                """);
        return XmlDocuments.bytes(xml.toString());
    }
}
