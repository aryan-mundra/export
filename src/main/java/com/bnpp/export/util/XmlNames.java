package com.bnpp.export.util;

import org.apache.commons.lang3.StringEscapeUtils;

import java.util.Locale;
import java.util.Objects;

/** XML element names and text escaping for the files the module writes. */
public final class XmlNames {

    private XmlNames() {
    }

    /**
     * The XML element for a name. Column names are free text, so each run of characters an element cannot hold
     * becomes "_" ("Country risk" is written Country_risk); a name may not start with a digit or with "xml".
     */
    public static String element(String name) {
        String value = (name == null ? "" : name.trim()).replaceAll("[^A-Za-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
        if (value.isEmpty()) {
            return "Field";
        }
        if (!Character.isLetter(value.charAt(0)) && value.charAt(0) != '_') {
            value = "_" + value;
        }
        return value.toLowerCase(Locale.ROOT).startsWith("xml") ? "_" + value : value;
    }

    /** Text escaped for XML 1.0 element content and attribute values. */
    public static String escape(Object value) {
        return StringEscapeUtils.escapeXml10(Objects.toString(value, ""));
    }
}
