package com.bnpp.export;

import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.bnpp.export.service.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comparing two versions of a list: the counts, the changed values per column, and the records page by page,
 * filtered by type, column and BNP ID, and as a CSV file.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:comparison;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "export.storage.directory=target/comparison"
})
@AutoConfigureMockMvc
class ComparisonTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SnapshotService snapshots;

    @Autowired
    private FormatTemplateRepository templates;

    @Autowired
    private JdbcTemplate jdbc;

    private String url(DataSnapshot to, DataSnapshot from, String what) {
        return "/api/data-versions/" + to.getId() + "/compare/" + from.getId() + "/" + what;
    }

    @Test
    void twoVersionsAreComparedBySummaryFilteredPagesAndFile() throws Exception {
        FormatTemplate list = templates.findAll().stream()
                .filter(template -> template.isActive() && template.getName().equals("Party Basic"))
                .findFirst().orElseThrow();
        DataSnapshot first = snapshots.resolve(42L, list, null).snapshot();

        // Two names and one nationality change; one list entry leaves the list.
        jdbc.update("UPDATE gs_party SET full_name = 'Renamed Five' WHERE bnp_id = 'BNP42000005'");
        jdbc.update("UPDATE gs_party SET full_name = 'Renamed Seven' WHERE bnp_id = 'BNP42000007'");
        jdbc.update("UPDATE gs_party SET nationality = 'ZZ' WHERE bnp_id = 'BNP42000009'");
        jdbc.update("DELETE FROM ver_version_member WHERE version_id = 42 AND record_id = 42000010");
        DataSnapshot second = snapshots.resolve(42L, list, null).snapshot();
        assertEquals("1.2", second.getLabel());

        // Summary: counts, and the changed values per column, most changed first.
        mockMvc.perform(get(url(second, first, "summary")).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromLabel").value("1.1"))
                .andExpect(jsonPath("$.toLabel").value("1.2"))
                .andExpect(jsonPath("$.sameLayout").value(true))
                .andExpect(jsonPath("$.counts.changed").value(3))
                .andExpect(jsonPath("$.counts.deleted").value(1))
                .andExpect(jsonPath("$.counts.inserted").value(0))
                .andExpect(jsonPath("$.columns[0].column").value("name"))
                .andExpect(jsonPath("$.columns[0].changed").value(2))
                .andExpect(jsonPath("$.columns[1].column").value("nationality"))
                .andExpect(jsonPath("$.columns[1].changed").value(1))
                .andExpect(jsonPath("$.columns.length()").value(2));

        // All differences, page by page, in record order.
        mockMvc.perform(get(url(second, first, "changes")).param("limit", "2").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.changes[0].bnpId").value("BNP42000005"))
                .andExpect(jsonPath("$.changes[0].kind").value("CHANGED"))
                .andExpect(jsonPath("$.changes[0].cells.length()").value(1))
                .andExpect(jsonPath("$.changes[0].cells[0].before").value("Party 42000005"))
                .andExpect(jsonPath("$.changes[0].cells[0].after").value("Renamed Five"));
        mockMvc.perform(get(url(second, first, "changes")).param("limit", "2").param("offset", "2").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.changes[0].bnpId").value("BNP42000009"))
                .andExpect(jsonPath("$.changes[1].bnpId").value("BNP42000010"))
                .andExpect(jsonPath("$.changes[1].kind").value("REMOVED"));

        // Filters: by type, by column, by BNP ID.
        mockMvc.perform(get(url(second, first, "changes")).param("kind", "CHANGED").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(3));
        mockMvc.perform(get(url(second, first, "changes")).param("column", "nationality").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.changes[0].bnpId").value("BNP42000009"))
                .andExpect(jsonPath("$.changes[0].cells[0].column").value("nationality"))
                .andExpect(jsonPath("$.changes[0].cells[0].after").value("ZZ"));
        mockMvc.perform(get(url(second, first, "changes")).param("q", "42000007").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get(url(second, first, "changes")).param("kind", "REMOVED").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.changes[0].cells.length()").value(3));

        // The other way round, the removed record is an added one.
        mockMvc.perform(get(url(first, second, "changes")).param("kind", "ADDED").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.changes[0].bnpId").value("BNP42000010"));

        // Refused: an unknown column, the same version twice.
        mockMvc.perform(get(url(second, first, "changes")).param("column", "nope").with(user("maker").roles("MAKER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(url(second, second, "summary")).with(user("maker").roles("MAKER")))
                .andExpect(status().isBadRequest());

        // The CSV: one line per changed value, with both values.
        var started = mockMvc.perform(get(url(second, first, "file")).with(user("maker").roles("MAKER")))
                .andExpect(request().asyncStarted())
                .andReturn();
        String csv = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("record_id,bnp_id,change,column,value_in_1.1,value_in_1.2"));
        assertTrue(csv.contains("42000005,BNP42000005,CHANGED,name,Party 42000005,Renamed Five"));
        assertTrue(csv.contains("42000010,BNP42000010,REMOVED,"));

        mockMvc.perform(get(url(second, first, "summary")).with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.counts.unchanged").value(second.getRowCount() - 3));
        mockMvc.perform(get(url(second, first, "changes")).param("kind", "WHATEVER").with(user("maker").roles("MAKER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("ALL, ADDED, REMOVED or CHANGED")));
    }
}
