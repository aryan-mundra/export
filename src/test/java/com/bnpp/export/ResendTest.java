package com.bnpp.export;

import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.model.ExportJob;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.SubscriberRepository;
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sending a stored data version again: the chosen subscribers get that version's rows, not today's data. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:resend;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "export.storage.directory=target/resend"
})
@AutoConfigureMockMvc
class ResendTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExportOrchestrator orchestrator;

    @Autowired
    private ExportJobRepository jobs;

    @Autowired
    private SubscriberRepository subscribers;

    @Autowired
    private JdbcTemplate jdbc;

    private JobDetailResponse await(Long jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            ExportJob job = jobs.findById(jobId).orElseThrow();
            if (!"PENDING".equals(job.getStatus()) && !"IN_PROGRESS".equals(job.getStatus())) {
                return orchestrator.getJob(jobId);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job did not finish");
    }

    private JobDetailResponse.SubscriberStatusResponse row(JobDetailResponse job, String name) {
        return job.subscribers().stream().filter(row -> name.equals(row.subscriberName())).findFirst().orElseThrow();
    }

    @Test
    void aStoredVersionIsSentAgainWithItsOriginalData() throws Exception {
        // 1.0, then the data changes and the next export makes 1.2.
        JobDetailResponse first = await(orchestrator.triggerExport(42L, "test").getId());
        Long v10 = row(first, "Compliance Team").snapshotId();
        jdbc.update("UPDATE gs_party SET full_name = 'Changed Party' WHERE bnp_id = 'BNP42000014'");
        JobDetailResponse second = await(orchestrator.triggerExport(42L, "test").getId());
        assertEquals("1.2", row(second, "Compliance Team").snapshotLabel());
        assertTrue(Files.readString(Path.of(row(second, "Compliance Team").filePath())).contains("Changed Party"));

        Long compliance = subscribers.findByNameIgnoreCase("Compliance Team").orElseThrow().getId();
        Long actimize = subscribers.findByNameIgnoreCase("Actimize").orElseThrow().getId();
        Long sun = subscribers.findByNameIgnoreCase("SUN").orElseThrow().getId();

        // Any subscriber can receive a stored version, in a format chosen for the whole send.
        mockMvc.perform(post("/api/exports/resend/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + compliance + "," + sun + "],\"formatType\":\"xml\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers[*].formatType", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("XML"))));
        mockMvc.perform(post("/api/exports/resend/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + sun + "],\"formatType\":\"PDF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Format must be one of")));
        String sunReview = mockMvc.perform(post("/api/exports/resend/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + sun + "],\"formatType\":\"CSV\"}"))
                .andReturn().getResponse().getContentAsString();
        String sunJob = mockMvc.perform(post("/api/exports/resend").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + sun + "],\"formatType\":\"CSV\",\"reviewToken\":\""
                                + JsonPath.read(sunReview, "$.reviewToken") + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var sunRow = row(await(((Number) JsonPath.read(sunJob, "$.id")).longValue()), "SUN");
        assertEquals("CSV", sunRow.formatType(), "SUN normally gets XML; this send was CSV");
        assertTrue(sunRow.filePath().endsWith(".csv"));
        assertTrue(Files.readString(Path.of(sunRow.filePath())).startsWith("bnp_id,"), "a CSV file with the version's columns");

        // Each subscriber can get its own format in one send: Compliance Team (normally CSV) as XML, SUN (normally XML) as CSV.
        mockMvc.perform(get("/api/formats").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItems("CSV", "XML")));
        String mixedBody = "\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + compliance + "," + sun + "],\"formats\":{\""
                + compliance + "\":\"xml\",\"" + sun + "\":\"CSV\"}";
        String mixedReview = mockMvc.perform(post("/api/exports/resend/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json").content("{" + mixedBody + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers[0].formatType").value("XML"))
                .andExpect(jsonPath("$.subscribers[1].formatType").value("CSV"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/exports/resend/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + sun + "],\"formats\":{\"" + sun + "\":\"PDF\"}}"))
                .andExpect(status().isBadRequest());
        String mixedJob = mockMvc.perform(post("/api/exports/resend").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{" + mixedBody + ",\"reviewToken\":\"" + JsonPath.read(mixedReview, "$.reviewToken") + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JobDetailResponse mixed = await(((Number) JsonPath.read(mixedJob, "$.id")).longValue());
        assertEquals("XML", row(mixed, "Compliance Team").formatType());
        assertTrue(row(mixed, "Compliance Team").filePath().endsWith(".xml"));
        assertEquals("CSV", row(mixed, "SUN").formatType());
        assertTrue(row(mixed, "SUN").filePath().endsWith(".csv"));

        // One subscriber can receive more than one format in the same send: a CSV file and an XML file.
        String bothBody = "\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + sun + "],\"formats\":{\"" + sun + "\":\"CSV,XML\"}";
        String bothReview = mockMvc.perform(post("/api/exports/resend/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json").content("{" + bothBody + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers.length()").value(2))
                .andExpect(jsonPath("$.subscribers[0].name").value("SUN"))
                .andExpect(jsonPath("$.subscribers[0].formatType").value("CSV"))
                .andExpect(jsonPath("$.subscribers[1].formatType").value("XML"))
                .andReturn().getResponse().getContentAsString();
        String bothJob = mockMvc.perform(post("/api/exports/resend").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{" + bothBody + ",\"reviewToken\":\"" + JsonPath.read(bothReview, "$.reviewToken") + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var sunFiles = await(((Number) JsonPath.read(bothJob, "$.id")).longValue()).subscribers().stream()
                .filter(item -> "SUN".equals(item.subscriberName())).toList();
        assertEquals(2, sunFiles.size());
        assertTrue(sunFiles.stream().anyMatch(item -> "CSV".equals(item.formatType()) && item.filePath().endsWith(".csv")));
        assertTrue(sunFiles.stream().anyMatch(item -> "XML".equals(item.formatType()) && item.filePath().endsWith(".xml")));

        String review = mockMvc.perform(post("/api/exports/resend/preview").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + compliance + "," + actimize + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("1.1"))
                .andExpect(jsonPath("$.subscribers[0].name").value("Compliance Team"))
                .andExpect(jsonPath("$.subscribers[0].records").value(row(first, "Compliance Team").recordsProcessed()))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(review, "$.reviewToken");

        // A stale review is refused.
        mockMvc.perform(post("/api/exports/resend").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + compliance + "],\"reviewToken\":\"" + token + "\"}"))
                .andExpect(status().isConflict());

        String job = mockMvc.perform(post("/api/exports/resend").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"snapshotId\":" + v10 + ",\"subscriberIds\":[" + compliance + "," + actimize + "],\"reviewToken\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JobDetailResponse resent = await(((Number) JsonPath.read(job, "$.id")).longValue());

        // Only the chosen subscribers, from the stored 1.1: the old name, not today's.
        assertEquals("COMPLETED", resent.status());
        assertEquals("RESEND", resent.mode());
        assertEquals("1.1", resent.snapshotLabel());
        assertEquals(2, resent.subscribers().size());
        var complianceRow = row(resent, "Compliance Team");
        assertEquals(v10, complianceRow.snapshotId());
        assertEquals("RESENT", complianceRow.snapshotOutcome());
        assertEquals(row(first, "Compliance Team").recordsProcessed(), complianceRow.recordsProcessed());
        String file = Files.readString(Path.of(complianceRow.filePath()));
        assertTrue(file.contains("Party 42000014"));
        assertFalse(file.contains("Changed Party"));
        assertEquals(Files.readString(Path.of(row(first, "Compliance Team").filePath())), file, "the resend matches the original file");
        assertTrue(complianceRow.archiveFileName() != null, "the resent file was delivered");
    }
}
