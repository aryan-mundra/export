package com.bnpp.export.format;

import org.apache.commons.lang3.StringEscapeUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class XmlDocuments {

    private static final String[] FIELDS = {
            "id", "name", "country", "dateOfBirth", "status", "sanctions", "worldcheck", "pfa", "action"
    };

    private XmlDocuments() {
    }

    static String esc(Object value) {
        return StringEscapeUtils.escapeXml11(Objects.toString(value, ""));
    }

    static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    static void appendFields(StringBuilder xml, Map<String, Object> record, String indent) {
        for (String field : FIELDS) {
            if (!record.containsKey(field) || record.get(field) == null) {
                continue;
            }
            String tag = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            xml.append(indent).append('<').append(tag).append('>')
                    .append(esc(record.get(field)))
                    .append("</").append(tag).append(">\n");
        }
    }

    static String header(String root, Long versionId, String subscriberName, int count) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <%s versionId="%s" subscriber="%s" recordCount="%d" generated="%s">
                """.formatted(root, versionId, esc(subscriberName), count, LocalDateTime.now());
    }

    static List<String> fieldNames() {
        return List.of(FIELDS);
    }
}
