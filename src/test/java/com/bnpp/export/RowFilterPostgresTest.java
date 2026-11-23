package com.bnpp.export;

import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.model.ExportJob;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.service.ExportOrchestrator;
import com.jayway.jsonpath.JsonPath;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Row filters on the postgres profile (H2 in PostgreSQL mode, loaded with db/postgres/regliss-mock.sql):
 * the data explorer, the review count (one SQL query) and the exported file must hold exactly the
 * list entries that pass the filters. The expected entries are worked out here in plain Java from
 * the raw tables, independent of the module's SQL.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:h2:mem:row_filter_postgres;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=regliss",
        "spring.datasource.password=regliss",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/postgres/regliss-mock.sql,file:db/postgres/ver-export-schema.sql",
        // Hibernate must find every column it uses in the hand-written schema.
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.encoding=UTF-8",
        "export.storage.directory=target/row-filter-postgres"
})
@AutoConfigureMockMvc
class RowFilterPostgresTest {

    private static final long VERSION = 42L;

    private static final String FIELDS = """
            [{"sourceTable":"GS_PARTY","sourceColumn":"BNP_ID","headerName":"BNP_ID"},
             {"sourceTable":"GS_PARTY","sourceColumn":"RISK_SCORE","headerName":"Risk"},
             {"sourceTable":"GS_ADDRESS","sourceColumn":"CITY","headerName":"City"},
             {"sourceTable":"GS_IDENTIFIER","sourceColumn":"ID_VALUE","headerName":"Identifiers"},
             {"sourceTable":"GS_SANCTION_PROGRAM","sourceColumn":"LISTED_ON","headerName":"Listed"}]""";

    /** City is Paris or London, or empty (every 13th entry has no address); risk ≥ 20; listed on or before 2021-12-31. */
    private static final String FILTERS = """
            [{"sourceTable":"gs_address","sourceColumn":"city","op":"in","values":["Paris","London"],"blank":true},
             {"sourceTable":"GS_PARTY","sourceColumn":"RISK_SCORE","op":"gte","values":["20"]},
             {"sourceTable":"GS_SANCTION_PROGRAM","sourceColumn":"LISTED_ON","op":"between","values":["","2021-12-31"]}]""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExportOrchestrator orchestrator;

    @Autowired
    private ExportJobRepository jobRepository;

    @Test
    void explorerReviewAndFileHoldExactlyTheEntriesThatPassTheFilters() throws Exception {
        Data data = load();
        List<String> expected = data.passing(bnp ->
                (data.cities(bnp).isEmpty() || data.cities(bnp).contains("Paris") || data.cities(bnp).contains("London"))
                        && data.risk(bnp) != null && data.risk(bnp) >= 20
                        && data.listed(bnp).stream().anyMatch(date -> !date.isAfter(LocalDate.of(2021, 12, 31))));
        assertTrue(expected.size() > 5 && expected.size() < data.entries.size(), "the filters should keep some entries, not all");
        assertTrue(expected.stream().anyMatch(bnp -> data.cities(bnp).isEmpty()), "an entry with an empty city should be kept");

        // The explorer: same count, same entries, in list order.
        String explored = explore(FILTERS, 500);
        assertEquals(expected.size(), ((Number) JsonPath.read(explored, "$.total")).intValue());
        assertEquals(data.entries.size(), ((Number) JsonPath.read(explored, "$.entries")).intValue());
        List<String> shown = JsonPath.read(explored, "$.rows[*].cells[0]");
        assertEquals(expected, shown);

        // Other operators against the same plain-Java rules.
        assertEquals(data.passing(bnp -> data.ids(bnp).stream().anyMatch(id -> id.toUpperCase().contains("AL"))).size(),
                total("""
                        [{"sourceTable":"GS_IDENTIFIER","sourceColumn":"ID_VALUE","op":"contains","values":["al"]}]"""));
        assertEquals(data.passing(bnp -> !data.cities(bnp).contains("Paris") && !data.cities(bnp).isEmpty()).size(),
                total("""
                        [{"sourceTable":"GS_ADDRESS","sourceColumn":"CITY","op":"not_in","values":["Paris"],"blank":true}]"""));
        assertEquals(data.passing(bnp -> data.risk(bnp) != null && data.risk(bnp) >= 10 && data.risk(bnp) <= 30).size(),
                total("""
                        [{"sourceTable":"GS_PARTY","sourceColumn":"RISK_SCORE","op":"between","values":["10","30"]}]"""));
        assertEquals(data.passing(bnp -> data.cities(bnp).isEmpty()).size(),
                total("""
                        [{"sourceTable":"GS_ADDRESS","sourceColumn":"CITY","op":"empty"}]"""));

        // The city facet counts entries that pass the other filters.
        String facet = mockMvc.perform(post("/api/format-templates/explore/facet").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":42,\"fields\":" + FIELDS + ",\"filters\":" + FILTERS + ",\"column\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("text"))
                .andReturn().getResponse().getContentAsString();
        List<String> parisCount = new ArrayList<>();
        ((List<Map<String, Object>>) JsonPath.read(facet, "$.values")).forEach(value -> {
            if ("Paris".equals(value.get("value"))) {
                parisCount.add(String.valueOf(value.get("count")));
            }
        });
        long parisExpected = data.passing(bnp -> data.cities(bnp).contains("Paris")
                && data.risk(bnp) != null && data.risk(bnp) >= 20
                && data.listed(bnp).stream().anyMatch(date -> !date.isAfter(LocalDate.of(2021, 12, 31)))).size();
        assertEquals(List.of(String.valueOf(parisExpected)), parisCount);

        // Save the template with its filters, approve it, and give it to a subscriber with no profile limits.
        String saved = mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\"Filtered\",\"formatType\":\"CSV\",\"fields\":" + FIELDS + ",\"filters\":" + FILTERS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters[0].summary").value("gs_address.city is Paris, London or empty"))
                .andExpect(jsonPath("$.filters[1].summary").value("gs_party.risk_score ≥ 20"))
                .andExpect(jsonPath("$.filters[2].summary").value("gs_sanction_program.listed_on is on or before 2021-12-31"))
                .andReturn().getResponse().getContentAsString();
        Number templateId = JsonPath.read(saved, "$.id");
        Number familyId = JsonPath.read(saved, "$.familyId");
        mockMvc.perform(post("/api/format-templates/" + templateId + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/subscribers").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\"Filtered\",\"formatType\":\"CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"templateId\":" + familyId + "}"))
                .andExpect(status().isOk());

        // Entries with a blank NAME or COUNTRY are quarantined, but only among those the filters keep.
        Set<String> blank = new HashSet<>(jdbc.queryForList(
                "SELECT BNP_ID FROM VER_VERSION_MEMBER WHERE VERSION_ID = ? AND (NAME IS NULL OR COUNTRY IS NULL)", String.class, VERSION));
        List<String> written = expected.stream().filter(bnp -> !blank.contains(bnp)).toList();
        long held = expected.size() - written.size();

        var review = orchestrator.preview(VERSION, null, null).subscribers().stream()
                .filter(row -> "Filtered".equals(row.name())).findFirst().orElseThrow();
        assertEquals(written.size(), review.records(), "review count (SQL) vs plain Java");
        assertEquals(held, review.quarantined());
        assertEquals(3, review.templateFilters().size());

        ExportJob job = await(orchestrator.triggerExport(VERSION, "test").getId());
        JobDetailResponse.SubscriberStatusResponse row = orchestrator.getJob(job.getId()).subscribers().stream()
                .filter(status -> "Filtered".equals(status.subscriberName())).findFirst().orElseThrow();
        assertEquals(written.size(), row.recordsProcessed());
        assertEquals(held, row.recordsQuarantined());
        assertNotNull(row.filePath());
        List<String> inFile = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(Files.readString(Path.of(row.filePath()), StandardCharsets.UTF_8)),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                inFile.add(record.get("BNP_ID"));
            }
        }
        assertEquals(written, inFile, "the file holds exactly the entries that pass the filters");
    }

    private String explore(String filters, int limit) throws Exception {
        return mockMvc.perform(post("/api/format-templates/explore").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":42,\"fields\":" + FIELDS + ",\"filters\":" + filters + ",\"limit\":" + limit + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int total(String filters) throws Exception {
        return ((Number) JsonPath.read(explore(filters, 1), "$.total")).intValue();
    }

    private ExportJob await(Long jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            ExportJob job = jobRepository.findById(jobId).orElseThrow();
            if (!"PENDING".equals(job.getStatus()) && !"IN_PROGRESS".equals(job.getStatus())) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("export did not finish");
    }

    /** The raw source rows, read without any of the module's code. */
    private Data load() {
        Data data = new Data();
        data.entries.addAll(jdbc.queryForList(
                "SELECT BNP_ID FROM VER_VERSION_MEMBER WHERE VERSION_ID = ? ORDER BY RECORD_ID", String.class, VERSION));
        jdbc.query("SELECT BNP_ID, CITY FROM GS_ADDRESS", rs -> {
            if (rs.getString(2) != null) {
                data.cities.computeIfAbsent(rs.getString(1), key -> new HashSet<>()).add(rs.getString(2));
            }
        });
        jdbc.query("SELECT BNP_ID, RISK_SCORE FROM GS_PARTY", rs -> {
            data.risk.put(rs.getString(1), rs.getObject(2) == null ? null : rs.getInt(2));
        });
        jdbc.query("SELECT BNP_ID, LISTED_ON FROM GS_SANCTION_PROGRAM", rs -> {
            Date date = rs.getDate(2);
            if (date != null) {
                data.listed.computeIfAbsent(rs.getString(1), key -> new ArrayList<>()).add(date.toLocalDate());
            }
        });
        jdbc.query("SELECT BNP_ID, ID_VALUE FROM GS_IDENTIFIER", rs -> {
            if (rs.getString(2) != null) {
                data.ids.computeIfAbsent(rs.getString(1), key -> new ArrayList<>()).add(rs.getString(2));
            }
        });
        return data;
    }

    private static final class Data {
        final List<String> entries = new ArrayList<>();
        final Map<String, Set<String>> cities = new HashMap<>();
        final Map<String, Integer> risk = new LinkedHashMap<>();
        final Map<String, List<LocalDate>> listed = new HashMap<>();
        final Map<String, List<String>> ids = new HashMap<>();

        Set<String> cities(String bnp) {
            return cities.getOrDefault(bnp, Set.of());
        }

        Integer risk(String bnp) {
            return risk.get(bnp);
        }

        List<LocalDate> listed(String bnp) {
            return listed.getOrDefault(bnp, List.of());
        }

        List<String> ids(String bnp) {
            return ids.getOrDefault(bnp, List.of());
        }

        List<String> passing(Predicate<String> rule) {
            return entries.stream().filter(rule).toList();
        }
    }
}
