package com.bnpp.export.service.impl;

import com.bnpp.export.model.VersionLayout;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shape of the XML: no wrapper per table, row ids as attributes, escaping, and names that would clash. */
class GroupXmlTest {

    private static VersionLayout.Group group() {
        return new VersionLayout.Group("person", "persons", "person_id", "person_id", "p", List.of(
                new VersionLayout.Table("p", null, "person", "persons", false, List.of("name", "note"), null),
                new VersionLayout.Table("n", "p", "name", "names", true, List.of("first"), "name_id"),
                new VersionLayout.Table("a", "p", "address", "addresses", false, List.of("city"), null)), List.of());
    }

    @Test
    void relatedRowsAreRepeatedWithoutAWrapperAndCarryTheirId() {
        Map<String, Object> doc = Map.of("values", Map.of("note", "A & B <x>"), "tables", Map.of(
                "n", List.of(Map.of("_id", "7", "values", Map.of("first", "John")), Map.of("_id", "8", "values", Map.of("first", "Johnny"))),
                "a", List.of(Map.of("values", Map.of("city", "Paris")))));

        assertEquals("""
                    <person person_id="101">
                      <note>A &amp; B &lt;x&gt;</note>
                      <name_row name_id="7">
                        <first>John</first>
                      </name_row>
                      <name_row name_id="8">
                        <first>Johnny</first>
                      </name_row>
                      <address>
                        <city>Paris</city>
                      </address>
                    </person>
                """, GroupXml.record(group(), "101", doc),
                "<name> would clash with the column 'name' of the person, so the rows are <name_row>; empty values are left out");
    }

    @Test
    void theFileHasASummaryHeaderAndOneSectionPerGroup() throws Exception {
        StringWriter out = new StringWriter();
        GroupXml.open(out, "Sanctions \"A\"", 2, 5, "2026-11-25T10:00:00", 3);
        GroupXml.openGroup(out, group(), 3);
        GroupXml.closeGroup(out, group());
        GroupXml.close(out);

        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Export list="Sanctions &quot;A&quot;" listVersion="2" dataVersion="5" generatedAt="2026-11-25T10:00:00" records="3">
                  <persons count="3">
                  </persons>
                </Export>
                """, out.toString());
    }

    @Test
    void theLiveFileHasNoDataVersion() throws Exception {
        StringWriter out = new StringWriter();
        GroupXml.open(out, "L", 1, null, "2026-11-25T10:00:00", 0);

        assertEquals("<Export list=\"L\" listVersion=\"1\" generatedAt=\"2026-11-25T10:00:00\" records=\"0\">",
                out.toString().lines().skip(1).findFirst().orElseThrow());
    }
}
