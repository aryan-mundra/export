package com.bnpp.export;

import com.bnpp.export.repository.FormatTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Export page by template: export the latest data (same version if unchanged, next one if changed). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:template_export;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "export.storage.directory=target/template-export"
})
@AutoConfigureMockMvc
class TemplateExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FormatTemplateRepository templates;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportLatestKeepsTheVersionUntilTheDataChanges() throws Exception {
        Long csv = templates.findAll().stream().filter(t -> "Party Basic".equals(t.getName())).findFirst().orElseThrow().getFamilyId();

        mockMvc.perform(get("/api/export-templates").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.templates[0].name").value("Party Basic"))
                .andExpect(jsonPath("$.templates[0].subscribers").value(2))
                .andExpect(jsonPath("$.templates[0].latestLabel").doesNotExist());

        // First export: version 1.1. Same data again: still 1.1.
        mockMvc.perform(post("/api/export-templates/" + csv + "/latest").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.snapshot.label").value("1.1"));
        mockMvc.perform(post("/api/export-templates/" + csv + "/latest").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.snapshot.label").value("1.1"));

        // The data changes: 1.2.
        jdbc.update("UPDATE gs_party SET full_name = 'Changed' WHERE bnp_id = 'BNP42000001'");
        mockMvc.perform(post("/api/export-templates/" + csv + "/latest").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.snapshot.label").value("1.2"))
                .andExpect(jsonPath("$.snapshot.changedRows").value(1));

        // Previous Versions lists only templates that have versions, searched by name.
        mockMvc.perform(get("/api/export-templates").param("withVersions", "true").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.templates[0].latestLabel").value("1.2"))
                .andExpect(jsonPath("$.templates[0].versionCount").value(2));
        mockMvc.perform(get("/api/export-templates").param("q", "detail").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.templates[0].name").value("Party Detail"));
    }
}
