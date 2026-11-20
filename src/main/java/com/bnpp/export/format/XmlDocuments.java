package com.bnpp.export.format;

import org.apache.commons.lang3.StringEscapeUtils;

import java.time.LocalDateTime;
import java.util.Objects;

final class XmlDocuments {

    private XmlDocuments() {
    }

    static String esc(Object value) {
        return StringEscapeUtils.escapeXml10(Objects.toString(value, ""));
    }

    static String header(String root, Long versionId, String subscriberName, long count, LocalDateTime generated) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <%s versionId="%s" subscriber="%s" recordCount="%d" generated="%s">
                """.formatted(root, versionId, esc(subscriberName), count, generated);
    }
}
