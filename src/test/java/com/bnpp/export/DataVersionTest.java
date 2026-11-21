package com.bnpp.export;

import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.model.DataSnapshot;
import com.bnpp.export.model.ExportJob;
import com.bnpp.export.model.FormatTemplate;
import com.bnpp.export.repository.DataSnapshotRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.bnpp.export.service.ExportOrchestrator;
import com.bnpp.export.service.SnapshotService;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data versions: identical data reuses its version, changed data gets the next one, every version
 * keeps its own rows, and old versions come back unchanged in any format.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:data_versions;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "export.storage.directory=target/data-versions"
})
@AutoConfigureMockMvc
class DataVersionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExportOrchestrator orchestrator;

    @Autowired
    private ExportJobRepository jobs;

    @Autowired
    private ExportJobSubscriberStatusRepository statuses;

    @Autowired
    private DataSnapshotRepository snapshots;

    @Autowired
    private FormatTemplateRepository templates;

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private JdbcTemplate jdbc;

    private JobDetailResponse export() throws InterruptedException {
        ExportJob job = orchestrator.triggerExport(42L, "test");
        for (int attempt = 0; attempt < 400; attempt++) {
            ExportJob current = jobs.findById(job.getId()).orElseThrow();
            if (!"PENDING".equals(current.getStatus()) && !"IN_PROGRESS".equals(current.getStatus())) {
                return orchestrator.getJob(job.getId());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("export did not finish");
    }

    private JobDetailResponse.SubscriberStatusResponse row(JobDetailResponse job, String subscriber) {
        return job.subscribers().stream().filter(row -> subscriber.equals(row.subscriberName())).findFirst().orElseThrow();
    }

    private DataSnapshot csvVersion(JobDetailResponse job) {
        return snapshots.findById(row(job, "Actimize").snapshotId()).orElseThrow();
    }

    private String download(Long snapshotId, String format) throws Exception {
        // The file streams asynchronously: wait for the async dispatch before reading it.
        var started = mockMvc.perform(get("/api/data-versions/" + snapshotId + "/file").param("format", format)
                        .with(user("maker").roles("MAKER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void identicalDataReusesItsVersionAndChangedDataGetsTheNext() throws Exception {
        // First export: one new version per template, shared by every subscriber on that template.
        JobDetailResponse first = export();
        assertEquals("COMPLETED", first.status());
        DataSnapshot v10 = csvVersion(first);
        assertEquals("1.0", v10.getLabel());
        assertEquals(400, v10.getRowCount());
        assertEquals(400, v10.getStoredRows());
        assertEquals("CREATED", row(first, "Actimize").snapshotOutcome());
        assertEquals(v10.getId(), row(first, "Compliance Team").snapshotId(), "subscribers on one template share one version");
        Set<Long> versionsOfFirst = first.subscribers().stream().map(JobDetailResponse.SubscriberStatusResponse::snapshotId).collect(Collectors.toSet());
        assertEquals(2, versionsOfFirst.size(), "one data version for the CSV template and one for the XML template");

        // Same data again: nothing new is stored.
        JobDetailResponse second = export();
        assertEquals(v10.getId(), csvVersion(second).getId());
        assertEquals("REUSED", row(second, "Actimize").snapshotOutcome());
        assertEquals(2, snapshots.count());
        assertEquals(1, snapshots.findById(v10.getId()).orElseThrow().getReuseCount());

        // One gs_ value changes: version 1.1, which stores only the changed row.
        jdbc.update("UPDATE gs_party SET full_name = 'Renamed Party' WHERE bnp_id = 'BNP42000005'");
        JobDetailResponse third = export();
        DataSnapshot v11 = csvVersion(third);
        assertEquals("1.1", v11.getLabel());
        assertEquals("CREATED", row(third, "Actimize").snapshotOutcome());
        assertEquals(1, v11.getStoredRows(), "only the changed row is stored again");
        assertEquals(1L, v11.getChangedRows());
        assertEquals(0L, v11.getInsertedRows());
        assertEquals(0L, v11.getDeletedRows());
        String thirdFile = Files.readString(Path.of(row(third, "Actimize").filePath()));

        // The old version still holds the old data, in either format, without reading the source.
        String oldCsv = download(v10.getId(), "CSV");
        assertTrue(oldCsv.contains("Party 42000005") && !oldCsv.contains("Renamed Party"), "1.0 must keep its original rows");
        String oldXml = download(v10.getId(), "XML");
        assertTrue(oldXml.contains("Party 42000005") && oldXml.contains("recordCount=\"400\""));
        assertTrue(download(v11.getId(), "CSV").contains("Renamed Party"));

        // Compare 1.1 with 1.0: one changed record, with the value before and after.
        mockMvc.perform(get("/api/data-versions/" + v11.getId() + "/compare/" + v10.getId()).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.changed").value(1))
                .andExpect(jsonPath("$.counts.unchanged").value(399))
                .andExpect(jsonPath("$.changes[0].recordId").value(42000005))
                .andExpect(jsonPath("$.changes[0].kind").value("CHANGED"))
                .andExpect(jsonPath("$.changes[0].cells[0].before").value("Party 42000005"))
                .andExpect(jsonPath("$.changes[0].cells[0].after").value("Renamed Party"));

        // A retry resends the data its first attempt was built from, even if the source has moved on.
        jdbc.update("UPDATE gs_party SET full_name = 'Renamed Again' WHERE bnp_id = 'BNP42000005'");
        var actimize = statuses.findById(row(third, "Actimize").id()).orElseThrow();
        actimize.setStatus("FAILED");
        statuses.save(actimize);
        JobDetailResponse retried = orchestrator.retry(third.id(), actimize.getId());
        assertEquals(v11.getId(), row(retried, "Actimize").snapshotId());
        assertEquals(thirdFile, Files.readString(Path.of(row(retried, "Actimize").filePath())), "the retry sends the same rows");
        assertFalse(download(v11.getId(), "CSV").contains("Renamed Again"));

        // The data goes back to how it was: the stored 1.0 is reused, no duplicate is made.
        jdbc.update("UPDATE gs_party SET full_name = 'Party 42000005' WHERE bnp_id = 'BNP42000005'");
        JobDetailResponse fourth = export();
        assertEquals(v10.getId(), csvVersion(fourth).getId());
        assertEquals("REUSED", row(fourth, "Actimize").snapshotOutcome());

        // A list entry leaves the list: 1.2 with one deleted record.
        jdbc.update("DELETE FROM ver_version_member WHERE version_id = 42 AND record_id = 42000010");
        DataSnapshot v12 = csvVersion(export());
        assertEquals("1.2", v12.getLabel());
        assertEquals(399, v12.getRowCount());
        assertEquals(0, v12.getStoredRows(), "every remaining row is already stored");
        assertEquals(1L, v12.getDeletedRows());

        // Two exports at the same moment on new data: one version is created, the other export reuses it.
        jdbc.update("UPDATE gs_party SET full_name = 'Concurrent' WHERE bnp_id = 'BNP42000006'");
        FormatTemplate csvTemplate = templates.findById(v10.getTemplateId()).orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<SnapshotService.Resolution> task = () -> {
            start.await();
            return snapshotService.resolve(42L, csvTemplate, null);
        };
        Future<SnapshotService.Resolution> a = pool.submit(task);
        Future<SnapshotService.Resolution> b = pool.submit(task);
        start.countDown();
        SnapshotService.Resolution ra = a.get();
        SnapshotService.Resolution rb = b.get();
        pool.shutdown();
        assertEquals(ra.snapshot().getId(), rb.snapshot().getId());
        assertTrue(ra.created() ^ rb.created(), "exactly one of the two creates the version");
        assertEquals("1.3", ra.snapshot().getLabel());

        // A new template version starts a new number: 2.0.
        String saved = mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("""
                                {"name":"CSV Export","formatType":"CSV","fields":[
                                  {"sourceTable":"gs_party","sourceColumn":"bnp_id","headerName":"bnp_id"},
                                  {"sourceTable":"gs_party","sourceColumn":"full_name","headerName":"party"}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/format-templates/" + JsonPath.read(saved, "$.id") + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isOk());
        DataSnapshot v20 = csvVersion(export());
        assertEquals("2.0", v20.getLabel());
        assertNotEquals(v10.getTemplateId(), v20.getTemplateId());

        // Every version is listed, and each keeps its own row count.
        mockMvc.perform(get("/api/data-versions").param("familyId", String.valueOf(v10.getFamilyId())).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].label", org.hamcrest.Matchers.containsInAnyOrder("2.0", "1.3", "1.2", "1.1", "1.0")));
        mockMvc.perform(get("/api/data-versions/" + v10.getId() + "/rows").param("limit", "2").with(user("maker").roles("MAKER")))
                .andExpect(jsonPath("$.total").value(400))
                .andExpect(jsonPath("$.rows[0].cells[0]").value("BNP42000001"));
        Map<String, Object> usage = JsonPath.read(mockMvc.perform(get("/api/data-versions/" + v10.getId()).with(user("maker").roles("MAKER")))
                .andReturn().getResponse().getContentAsString(), "$.usage[0]");
        assertFalse(usage.isEmpty());
        assertEquals(List.of("CREATED", "REUSED"), List.copyOf(statuses.findBySnapshotIdOrderByIdDesc(v10.getId()).stream()
                .map(status -> status.getSnapshotOutcome()).collect(Collectors.toCollection(java.util.TreeSet::new))));
    }
}
