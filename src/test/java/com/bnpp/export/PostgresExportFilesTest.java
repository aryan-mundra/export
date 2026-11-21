package com.bnpp.export;

import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.model.ExportJob;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.ExportOrchestrator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The module's main job, checked end to end on the postgres profile: read list records and gs_ values
 * from the database and write them into correct CSV and XML files.
 *
 * The database is H2 in PostgreSQL mode, loaded with db/postgres/regliss-mock.sql (the same file used on a
 * real PostgreSQL). Every expected value is read back with plain SQL written here, independent of the
 * module's code, and every file is read with a real CSV or XML parser.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:h2:mem:postgres_export_files;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=regliss",
        "spring.datasource.password=regliss",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/postgres/regliss-mock.sql",
        "spring.sql.init.encoding=UTF-8",
        "export.storage.directory=target/postgres-export-files"
})
@AutoConfigureMockMvc
class PostgresExportFilesTest {

    private static final long VERSION = 42L;

    /** The same columns, in the same order, on both templates. */
    private static final List<Column> COLUMNS = List.of(
            new Column("GS_PARTY", "BNP_ID", "BNP ID", "BnpId"),
            new Column("GS_PARTY", "FULL_NAME", "Name", "Name"),
            new Column("GS_PARTY", "NATIONALITY", "Nationality", "Nationality"),
            new Column("GS_ADDRESS", "STREET", "Street", "Street"),
            new Column("GS_IDENTIFIER", "ID_VALUE", "Identifiers", "Identifiers"),
            new Column("GS_COUNTRY_RISK", "RISK_LEVEL", "Country risk", "CountryRisk"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExportOrchestrator orchestrator;

    @Autowired
    private ExportJobRepository jobRepository;

    @Autowired
    private SubscriberRepository subscriberRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.bnpp.export.repository.QuarantineRepository quarantineRepository;

    @Test
    void csvAndXmlFilesHoldExactlyTheDatabaseValues() throws Exception {
        // Every subscriber gets the template of its format that this test builds.
        Long csvTemplate = approveTemplate("CSV", true);
        Long xmlTemplate = approveTemplate("XML", false);
        for (SubscriberProfile subscriber : subscriberRepository.findAll()) {
            subscriber.setTemplateFamilyId("CSV".equals(subscriber.getFormatType()) ? csvTemplate : xmlTemplate);
            subscriberRepository.save(subscriber);
        }

        // The review step counts in SQL; the export streams the records. Both must agree.
        var review = orchestrator.preview(VERSION, null, null);

        ExportJob job = await(orchestrator.triggerExport(VERSION, "test").getId());
        JobDetailResponse detail = orchestrator.getJob(job.getId());
        for (var row : detail.subscribers()) {
            var reviewed = review.subscribers().stream().filter(p -> p.subscriberId().equals(row.subscriberId())).findFirst().orElseThrow();
            assertEquals(reviewed.records(), row.recordsProcessed(), row.subscriberName() + ": review count vs records written");
            assertEquals(reviewed.quarantined(), row.recordsQuarantined(), row.subscriberName() + ": review count vs records quarantined");
        }

        Set<String> checkedRecords = new TreeSet<>();
        int csvFiles = 0;
        int xmlFiles = 0;
        for (JobDetailResponse.SubscriberStatusResponse row : detail.subscribers()) {
            SubscriberProfile subscriber = subscriberRepository.findById(row.subscriberId()).orElseThrow();
            List<Map<String, String>> expected = expectedRows(subscriber);
            if (row.filePath() == null) {
                assertTrue(expected.isEmpty(), subscriber.getName() + " has records but no file was built");
                continue;
            }
            byte[] bytes = Files.readAllBytes(Path.of(row.filePath()));
            List<Map<String, String>> actual;
            if ("CSV".equals(subscriber.getFormatType())) {
                actual = readCsv(bytes);
                csvFiles++;
            } else {
                actual = readXml(bytes, subscriber.getName(), expected.size());
                xmlFiles++;
            }
            assertEquals(expected, actual, subscriber.getName() + " " + subscriber.getFormatType() + " file does not match the database");
            expected.forEach(record -> checkedRecords.add(record.get("BNP_ID")));
        }

        assertTrue(csvFiles > 0, "no CSV file was checked");
        assertTrue(xmlFiles > 0, "no XML file was checked");
        // The awkward values are in files of both formats: &, <, quotes and commas, non-ASCII, padding, line breaks
        for (String id : List.of("42000014", "42000020", "42000034", "42000040", "42000054", "42000060", "42000080", "42000094")) {
            assertTrue(checkedRecords.contains("BNP" + id), "record " + id + " was not in any checked file");
        }

        // Record 42000041 has no COUNTRY, so it is quarantined and SHINE's file is held, not sent.
        assertEquals("HELD", job.getStatus());
        var shine = detail.subscribers().stream().filter(row -> "SHINE".equals(row.subscriberName())).findFirst().orElseThrow();
        assertEquals("HELD", shine.status());
        assertNotNull(shine.fileName(), "a held file is still built");
        assertEquals(null, shine.archiveFileName(), "a held file must not be sent");
        var quarantined = quarantineRepository.findBySubscriberStatusIdAndPatchAppliedFalse(shine.id());
        assertEquals(List.of("42000041"), quarantined.stream().map(q -> q.getRecordIdentifier()).toList());
        assertEquals("Country is blank", quarantined.get(0).getQuarantineReason());

        // Accepting the omission sends the file, records who accepted it, and the archive proves what was sent.
        mockMvc.perform(post("/api/jobs/" + job.getId() + "/subscribers/" + shine.id() + "/accept").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk());
        var released = orchestrator.getJob(job.getId()).subscribers().stream().filter(row -> row.id().equals(shine.id())).findFirst().orElseThrow();
        assertEquals("COMPLETED", released.status());
        assertNotNull(released.archiveFileName(), "accepted file was not sent");
        assertTrue(quarantineRepository.findBySubscriberStatusIdAndPatchAppliedFalse(shine.id()).isEmpty(), "omission still open");
        assertEquals("maker", quarantineRepository.findAll().stream()
                .filter(q -> shine.id().equals(q.getSubscriberStatusId())).findFirst().orElseThrow().getAcceptedBy());
        mockMvc.perform(post("/api/jobs/" + job.getId() + "/subscribers/" + shine.id() + "/proof").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result").value("MATCH"));
        assertEquals("COMPLETED", jobRepository.findById(job.getId()).orElseThrow().getStatus());
    }

    // ---- expected values, straight from the database ----

    private List<Map<String, String>> expectedRows(SubscriberProfile subscriber) {
        String flag = switch (subscriber.getScope()) {
            case "SANCTIONS" -> " AND ON_SANCTIONS = 1";
            case "WORLDCHECK" -> " AND ON_WORLDCHECK = 1";
            case "PFA" -> " AND ON_PFA = 1";
            default -> "";
        };
        // Records with a blank required field are quarantined, not written.
        String sql = "SELECT RECORD_ID, BNP_ID, NAME, COUNTRY, TO_CHAR(RECORD_DATE, 'YYYY-MM-DD') AS RECORD_DATE, ACTION"
                + " FROM VER_VERSION_MEMBER WHERE VERSION_ID = ?" + flag
                + " AND BUSINESS_LINE = ? AND REGION = ?"
                + " AND NAME IS NOT NULL AND COUNTRY IS NOT NULL AND RECORD_DATE IS NOT NULL AND ACTION IS NOT NULL"
                + " ORDER BY RECORD_ID";
        List<Map<String, String>> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            long id = rs.getLong("RECORD_ID");
            Map<String, String> row = new LinkedHashMap<>();
            // Each row is one list entry (which entries, and which are quarantined, is decided above);
            // every value in it comes from a gs_ table.
            // Every gs_ table is matched to the entry on BNP_ID.
            String bnpId = rs.getString("BNP_ID");
            row.put("BNP_ID", joined("SELECT BNP_ID FROM GS_PARTY WHERE BNP_ID = ?", bnpId));
            row.put("FULL_NAME", joined("SELECT FULL_NAME FROM GS_PARTY WHERE BNP_ID = ? ORDER BY FULL_NAME", bnpId));
            row.put("NATIONALITY", joined("SELECT NATIONALITY FROM GS_PARTY WHERE BNP_ID = ? ORDER BY NATIONALITY", bnpId));
            row.put("STREET", joined("SELECT STREET FROM GS_ADDRESS WHERE BNP_ID = ? ORDER BY STREET", bnpId));
            row.put("ID_VALUE", joined("SELECT ID_VALUE FROM GS_IDENTIFIER WHERE BNP_ID = ? ORDER BY ID_VALUE", bnpId));
            row.put("RISK_LEVEL", joined("SELECT RISK_LEVEL FROM GS_COUNTRY_RISK WHERE BNP_ID = ? ORDER BY RISK_LEVEL", bnpId));
            rows.add(row);
        }, VERSION, subscriber.getBusinessLine(), subscriber.getRegion());
        return rows;
    }

    private String joined(String sql, Object key) {
        List<String> values = jdbc.queryForList(sql, String.class, key);
        return values.isEmpty() ? null : String.join("; ", values);
    }

    // ---- reading the files ----

    private List<Map<String, String>> readCsv(byte[] bytes) throws Exception {
        String text = new String(bytes, StandardCharsets.UTF_8);
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            assertEquals(COLUMNS.stream().map(Column::csvHeader).toList(), parser.getHeaderNames(), "CSV header row");
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                assertEquals(COLUMNS.size(), record.size(), "CSV line " + record.getRecordNumber() + " has the wrong number of cells");
                Map<String, String> row = new LinkedHashMap<>();
                for (Column column : COLUMNS) {
                    String value = record.get(column.csvHeader());
                    row.put(column.key(), value.isEmpty() ? null : value);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private List<Map<String, String>> readXml(byte[] bytes, String subscriber, int expectedCount) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        Element root = document.getDocumentElement();
        assertEquals("Export", root.getTagName());
        assertEquals(String.valueOf(VERSION), root.getAttribute("versionId"));
        assertEquals(subscriber, root.getAttribute("subscriber"));
        assertEquals(String.valueOf(expectedCount), root.getAttribute("recordCount"));
        List<Map<String, String>> rows = new ArrayList<>();
        NodeList records = root.getElementsByTagName("Record");
        for (int i = 0; i < records.getLength(); i++) {
            Element record = (Element) records.item(i);
            Map<String, String> values = new LinkedHashMap<>();
            for (Node child = record.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element) {
                    assertFalse(values.containsKey(element.getTagName()), "element " + element.getTagName() + " repeated");
                    values.put(element.getTagName(), element.getTextContent());
                }
            }
            assertNotNull(values.get("BnpId"), "record without <BnpId>");
            assertEquals("BNP" + record.getAttribute("id"), values.get("BnpId"), "BNP_ID of the record");
            Map<String, String> row = new LinkedHashMap<>();
            for (Column column : COLUMNS) {
                row.put(column.key(), values.get(column.xmlName()));
            }
            rows.add(row);
        }
        return rows;
    }

    // ---- set-up ----

    /** Saves and approves a template; returns its id (family id) for subscribers to choose. */
    private Long approveTemplate(String format, boolean csv) throws Exception {
        StringBuilder fields = new StringBuilder();
        for (Column column : COLUMNS) {
            if (!fields.isEmpty()) {
                fields.append(',');
            }
            fields.append("{\"sourceTable\":").append(column.table() == null ? "null" : "\"" + column.table() + "\"")
                    .append(",\"sourceColumn\":\"").append(column.column())
                    .append("\",\"headerName\":\"").append(csv ? column.csvHeader() : column.xmlName()).append('"');
            if (column.joinColumn() != null) {
                fields.append(",\"joinColumn\":\"").append(column.joinColumn())
                        .append("\",\"joinField\":\"").append(column.joinField()).append('"');
            }
            fields.append('}');
        }
        MvcResult saved = mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\"" + format + " check\",\"formatType\":\"" + format + "\",\"fields\":[" + fields + "]}"))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/format-templates/" + id + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isOk());
        Number familyId = com.jayway.jsonpath.JsonPath.read(saved.getResponse().getContentAsString(), "$.familyId");
        return familyId.longValue();
    }

    private ExportJob await(Long id) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ExportJob job = jobRepository.findById(id).orElseThrow();
            if (List.of("COMPLETED", "FAILED", "HELD").contains(job.getStatus())) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Export job " + id + " did not finish");
    }

    private record Column(String table, String column, String csvHeader, String xmlName, String joinColumn, String joinField) {
        Column(String table, String column, String csvHeader, String xmlName) {
            this(table, column, csvHeader, xmlName, null, null);
        }

        String key() {
            return column;
        }
    }
}
