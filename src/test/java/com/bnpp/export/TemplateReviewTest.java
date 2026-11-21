package com.bnpp.export;

import com.bnpp.export.model.ExportAuditLog;
import com.bnpp.export.repository.ExportAuditLogRepository;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The maker / approver review cycle of a template: request changes, resubmit, replace, reject, approve. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:template_review;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "export.storage.directory=target/template-review"
})
@AutoConfigureMockMvc
class TemplateReviewTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FormatTemplateRepository templateRepository;

    @Autowired
    private ExportAuditLogRepository auditRepository;

    private String submit(String name, String headers) throws Exception {
        return mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"formatType\":\"CSV\",\"fields\":[" + headers + "],"
                                + "\"filters\":[{\"sourceTable\":\"gs_address\",\"sourceColumn\":\"city\",\"op\":\"in\",\"values\":[\"Paris\"]}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void approversReturnRejectAndApproveWithTheReasonRecorded() throws Exception {
        String columns = "{\"sourceTable\":\"GS_PARTY\",\"sourceColumn\":\"BNP_ID\",\"headerName\":\"id\"},"
                + "{\"sourceTable\":\"gs_address\",\"sourceColumn\":\"city\",\"headerName\":\"city\"}";
        Long v1 = id(submit("Reviewed", columns));

        // Only an approver decides, and a reason is required to send back or reject.
        mockMvc.perform(post("/api/format-templates/" + v1 + "/request-changes").with(user("maker").roles("MAKER"))
                        .contentType("application/json").content("{\"comment\":\"please change this\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/format-templates/" + v1 + "/request-changes").with(user("approver").roles("APPROVER"))
                        .contentType("application/json").content("{\"comment\":\"too short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("at least 10 characters")));
        mockMvc.perform(post("/api/format-templates/" + v1 + "/request-changes").with(user("approver").roles("APPROVER"))
                        .contentType("application/json").content("{\"comment\":\"Rename id to bnp_id for Actimize\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.reviewedBy").value("approver"))
                .andExpect(jsonPath("$.reviewComment").value("Rename id to bnp_id for Actimize"));
        // A returned version cannot be approved any more...
        mockMvc.perform(post("/api/format-templates/" + v1 + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isConflict());
        // ...nor chosen by a subscriber.
        mockMvc.perform(post("/api/subscribers").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\"Too early\",\"formatType\":\"CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"templateId\":" + v1 + "}"))
                .andExpect(status().isBadRequest());

        // The maker resubmits as version 2; saving again before review replaces it with version 3.
        String v2Json = submit("Reviewed", columns.replace("\"headerName\":\"id\"", "\"headerName\":\"bnp_id\""));
        assertEquals(2, (int) JsonPath.read(v2Json, "$.versionNumber"));
        Long v2 = id(v2Json);
        Long v3 = id(submit("Reviewed", columns.replace("\"headerName\":\"id\"", "\"headerName\":\"BnpId\"")));
        assertEquals("SUPERSEDED", templateRepository.findById(v2).orElseThrow().getStatus());
        mockMvc.perform(post("/api/format-templates/" + v2 + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Version 2 was replaced by version 3 before it was reviewed. Review the newest version."));

        // Four eyes: an approver cannot decide on a version they submitted.
        String own = mockMvc.perform(post("/api/format-templates").with(user("approver").roles("APPROVER"))
                        .contentType("application/json")
                        .content("{\"name\":\"Own work\",\"formatType\":\"CSV\",\"fields\":[" + columns + "]}"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/format-templates/" + id(own) + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You submitted this version. Another approver must review it."));

        // Approve with a comment: the version is in use and keeps its row filter.
        mockMvc.perform(post("/api/format-templates/" + v3 + "/approve").with(user("approver").roles("APPROVER"))
                        .contentType("application/json").content("{\"comment\":\"Matches the Actimize mapping\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.reviewComment").value("Matches the Actimize mapping"))
                .andExpect(jsonPath("$.filters[0].summary").value("gs_address.city is Paris"));

        // A later version can be rejected; the approved version stays in use.
        Long v4 = id(submit("Reviewed", columns));
        mockMvc.perform(post("/api/format-templates/" + v4 + "/reject").with(user("approver").roles("APPROVER"))
                        .contentType("application/json").content("{\"comment\":\"Going back to id breaks the mapping\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.active").value(false));
        assertTrue(templateRepository.findById(v3).orElseThrow().isActive(), "the approved version must stay in use");

        // Every step is in the audit log, with who did it.
        List<String> actions = auditRepository.findAll().stream()
                .filter(entry -> "TEMPLATE".equals(entry.getEntityType()))
                .filter(entry -> List.of(v1, v2, v3, v4).contains(entry.getEntityId()))
                .map(ExportAuditLog::getAction)
                .toList();
        assertEquals(List.of("TEMPLATE_SUBMITTED", "TEMPLATE_CHANGES_REQUESTED", "TEMPLATE_RESUBMITTED", "TEMPLATE_SUPERSEDED",
                "TEMPLATE_SUBMITTED", "TEMPLATE_APPROVED", "TEMPLATE_SUBMITTED", "TEMPLATE_REJECTED"), actions);
        assertTrue(auditRepository.findAll().stream().anyMatch(entry -> "TEMPLATE_REJECTED".equals(entry.getAction())
                && "approver".equals(entry.getActor()) && entry.getDetails().contains("Going back to id breaks the mapping")));
    }
}
