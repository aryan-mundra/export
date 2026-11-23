package com.bnpp.export;

import com.bnpp.export.model.ExportJob;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.ExportOrchestrator;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Export page's catalogs (lists, subscribers) and exporting to chosen subscribers only. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:export_catalog;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "export.storage.directory=target/export-catalog"
})
@AutoConfigureMockMvc
class ExportCatalogTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubscriberRepository subscribers;

    @Autowired
    private ExportJobRepository jobs;

    @Autowired
    private ExportOrchestrator orchestrator;

    @Test
    void listsAndSubscribersAreSearchedAndPagedAndAnExportCanTargetSomeSubscribers() throws Exception {
        // One row per list: its newest certified version operators can export (77 is hidden, so Sanctions is 42).
        mockMvc.perform(get("/api/lists").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.lists[*].listCode", containsInAnyOrder("SANCTIONS", "WORLDCHECK", "PFA")))
                .andExpect(jsonPath("$.lists[?(@.listCode == 'SANCTIONS')].versionId").value(org.hamcrest.Matchers.contains(42)));
        mockMvc.perform(get("/api/lists").param("q", "world").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.lists[0].listName").value("World-Check"));
        mockMvc.perform(get("/api/lists").param("limit", "2").param("sort", "name").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.lists", hasSize(2)))
                .andExpect(jsonPath("$.lists[0].listName").value("PFA"));

        // Subscribers: searched and paged on the server.
        mockMvc.perform(get("/api/subscribers/search").param("limit", "2").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.subscribers", hasSize(2)));
        mockMvc.perform(get("/api/subscribers/search").param("format", "CSV").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get("/api/subscribers/search").param("q", "shi").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.subscribers[0].name").value("SHINE"));

        // An export to two chosen subscribers reviews and sends to those two only.
        Long sun = subscribers.findByNameIgnoreCase("SUN").orElseThrow().getId();
        Long actimize = subscribers.findByNameIgnoreCase("Actimize").orElseThrow().getId();
        String body = "{\"versionId\":43,\"subscriberIds\":[" + sun + "," + actimize + "]}";
        String review = mockMvc.perform(post("/api/exports/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.subscribers", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(review, "$.reviewToken");
        // The token of a two-subscriber review does not authorise sending to everyone.
        mockMvc.perform(post("/api/exports").with(user("maker").roles("MAKER")).contentType("application/json")
                        .content("{\"versionId\":43,\"reviewToken\":\"" + token + "\"}"))
                .andExpect(status().isConflict());
        String job = mockMvc.perform(post("/api/exports").with(user("maker").roles("MAKER")).contentType("application/json")
                        .content("{\"versionId\":43,\"reviewToken\":\"" + token + "\",\"subscriberIds\":[" + sun + "," + actimize + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long jobId = ((Number) JsonPath.read(job, "$.id")).longValue();
        for (int attempt = 0; attempt < 200; attempt++) {
            ExportJob current = jobs.findById(jobId).orElseThrow();
            if (!"PENDING".equals(current.getStatus()) && !"IN_PROGRESS".equals(current.getStatus())) {
                break;
            }
            Thread.sleep(50);
        }
        var detail = orchestrator.getJob(jobId);
        assertEquals("COMPLETED", detail.status());
        assertEquals(2, detail.subscribers().size());
        assertEquals(2, detail.totalSubscribers());

        // The list now shows as exported, first under "recently exported".
        mockMvc.perform(get("/api/lists").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.lists[0].listCode").value("WORLDCHECK"))
                .andExpect(jsonPath("$.lists[0].lastExport").isNotEmpty());
        mockMvc.perform(get("/api/dashboard").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.recentJobs[0].subscriberIds").value(Math.min(sun, actimize) + "," + Math.max(sun, actimize)));
    }
}
