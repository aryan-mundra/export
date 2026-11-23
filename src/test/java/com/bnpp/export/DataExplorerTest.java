package com.bnpp.export;

import com.bnpp.export.model.ExportJob;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.service.ExportOrchestrator;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The data explorer and row filters in H2 mock mode, where list entries come from the mock reader
 * (and are mirrored in VER_VERSION_MEMBER) and the review counts by streaming.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:data_explorer;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "export.storage.directory=target/data-explorer"
})
@AutoConfigureMockMvc
class DataExplorerTest {

    private static final String FIELDS = """
            [{"sourceTable":"GS_PARTY","sourceColumn":"BNP_ID","headerName":"bnp_id"},
             {"sourceTable":"gs_address","sourceColumn":"city","headerName":"City"},
             {"sourceTable":"GS_PARTY","sourceColumn":"RISK_SCORE","headerName":"Risk"}]""";

    private static final String FILTERS = """
            [{"sourceTable":"gs_address","sourceColumn":"city","op":"in","values":["Paris","London"]},
             {"sourceTable":"GS_PARTY","sourceColumn":"RISK_SCORE","op":"gte","values":["50"]}]""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExportOrchestrator orchestrator;

    @Autowired
    private ExportJobRepository jobRepository;

    @Test
    void explorerPagesSortsAndFiltersTheRowsOfATemplate() throws Exception {
        // No filters: every list entry, a page at a time, in list order.
        mockMvc.perform(explore("[]", ",\"limit\":100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(42))
                .andExpect(jsonPath("$.total").value(400))
                .andExpect(jsonPath("$.entries").value(400))
                .andExpect(jsonPath("$.more").value(true))
                .andExpect(jsonPath("$.rows", hasSize(100)))
                .andExpect(jsonPath("$.rows[0].cells[0]").value("BNP42000001"))
                .andExpect(jsonPath("$.columns[2].kind").value("number"))
                .andExpect(jsonPath("$.columns[1].kind").value("text"));
        mockMvc.perform(explore("[]", ",\"offset\":390,\"limit\":100,\"withTotal\":false"))
                .andExpect(jsonPath("$.rows", hasSize(10)))
                .andExpect(jsonPath("$.more").value(false))
                .andExpect(jsonPath("$.total").doesNotExist());
        // Sorted on screen by risk, highest first.
        String sorted = mockMvc.perform(explore("[]", ",\"sortColumn\":2,\"sortDirection\":\"desc\",\"limit\":3"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(List.of("99", "99", "99"), JsonPath.read(sorted, "$.rows[*].cells[2]"));

        // Filters: the expected count straight from the mock tables.
        Integer expected = jdbc.queryForObject("""
                SELECT COUNT(*) FROM VER_VERSION_MEMBER m WHERE m.VERSION_ID = 42
                   AND EXISTS (SELECT 1 FROM "gs_address" a WHERE a."bnp_id" = m.BNP_ID AND a."city" IN ('Paris', 'London'))
                   AND EXISTS (SELECT 1 FROM GS_PARTY p WHERE p.BNP_ID = m.BNP_ID AND p.RISK_SCORE >= 50)""", Integer.class);
        assertTrue(expected != null && expected > 0 && expected < 400);
        mockMvc.perform(explore(FILTERS, ""))
                .andExpect(jsonPath("$.total").value(expected))
                .andExpect(jsonPath("$.entries").value(400));

        // Facets: distinct values with counts for text, a range for numbers.
        mockMvc.perform(post("/api/format-templates/explore/facet").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"fields\":" + FIELDS + ",\"filters\":[],\"column\":1}"))
                .andExpect(jsonPath("$.kind").value("text"))
                .andExpect(jsonPath("$.values", hasSize(10)))
                .andExpect(jsonPath("$.values[0].value").value("Berlin"))
                .andExpect(jsonPath("$.values[0].count").value(40))
                .andExpect(jsonPath("$.blank").value(0));
        mockMvc.perform(post("/api/format-templates/explore/facet").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"fields\":" + FIELDS + ",\"filters\":[],\"column\":1,\"search\":\"par\"}"))
                .andExpect(jsonPath("$.values", hasSize(1)))
                .andExpect(jsonPath("$.values[0].value").value("Paris"));
        mockMvc.perform(post("/api/format-templates/explore/facet").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"fields\":" + FIELDS + ",\"filters\":[],\"column\":2}"))
                .andExpect(jsonPath("$.kind").value("number"))
                .andExpect(jsonPath("$.min").value("0"))
                .andExpect(jsonPath("$.max").value("99"));

        // Bad filters are refused with a reason a user can act on.
        mockMvc.perform(explore("[{\"sourceTable\":\"GS_PARTY\",\"sourceColumn\":\"NATIONALITY\",\"op\":\"in\",\"values\":[\"FR\"]}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A filter on GS_PARTY.NATIONALITY needs that column on the list"));
        mockMvc.perform(explore("[{\"sourceTable\":\"GS_PARTY\",\"sourceColumn\":\"RISK_SCORE\",\"op\":\"contains\",\"values\":[\"5\"]}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("is not a filter for the number column")));
        mockMvc.perform(explore("[{\"sourceTable\":\"GS_PARTY\",\"sourceColumn\":\"RISK_SCORE\",\"op\":\"gt\",\"values\":[\"high\"]}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("is not a number")));
        mockMvc.perform(explore("[{\"sourceTable\":\"gs_address\",\"sourceColumn\":\"city\",\"op\":\"in\",\"values\":[]}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose at least one value to filter gs_address.city"));

        // Saved, approved and exported: the file holds exactly the filtered entries, none are quarantined.
        String saved = mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\"Paris and London\",\"formatType\":\"CSV\",\"fields\":" + FIELDS + ",\"filters\":" + FILTERS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters", hasSize(2)))
                .andExpect(jsonPath("$.filters[0].summary").value("gs_address.city is Paris, London"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/format-templates/" + JsonPath.read(saved, "$.id") + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/subscribers").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\"Filter check\",\"formatType\":\"CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"templateId\":"
                                + JsonPath.read(saved, "$.familyId") + "}"))
                .andExpect(status().isOk());
        var review = orchestrator.preview(42L, null, null).subscribers().stream()
                .filter(row -> "Filter check".equals(row.name())).findFirst().orElseThrow();
        assertEquals(expected, review.records());
        assertEquals(0, review.quarantined());

        ExportJob job = await(orchestrator.triggerExport(42L, "test").getId());
        var row = orchestrator.getJob(job.getId()).subscribers().stream()
                .filter(status -> "Filter check".equals(status.subscriberName())).findFirst().orElseThrow();
        assertEquals(expected, row.recordsProcessed());
        assertEquals(0, row.recordsQuarantined());
        List<String> lines = Files.readAllLines(Path.of(row.filePath()));
        assertEquals("bnp_id,City,Risk", lines.get(0));
        assertEquals(expected, lines.size() - 1);
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",");
            assertTrue(List.of("Paris", "London").contains(cells[1]) && Integer.parseInt(cells[2]) >= 50, line);
        }
    }

    private org.springframework.test.web.servlet.RequestBuilder explore(String filters, String extra) {
        return post("/api/format-templates/explore").with(user("maker").roles("MAKER"))
                .contentType("application/json")
                .content("{\"fields\":" + FIELDS + ",\"filters\":" + filters + extra + "}");
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
}
