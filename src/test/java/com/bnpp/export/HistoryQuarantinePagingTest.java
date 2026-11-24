package com.bnpp.export;

import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.service.ExportService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * History ({@code GET /api/jobs}) and Quarantine ({@code GET /api/quarantine}) are paged in the database:
 * each page is a slice of the full, newest-first list; the total counts every match; the limit is kept
 * between 1 and 100; a search pages the same way.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:h2:mem:history_paging;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=regliss",
        "spring.datasource.password=regliss",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:db/postgres/regliss-mock.sql,file:db/postgres/ver-export-schema.sql",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.encoding=UTF-8",
        "export.storage.directory=target/history-paging-files"
})
@AutoConfigureMockMvc
class HistoryQuarantinePagingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExportService exports;

    @Autowired
    private ExportJobRepository jobs;

    @Test
    void historyAndQuarantinePageInTheDatabase() throws Exception {
        for (int run = 0; run < 3; run++) {
            await(exports.triggerExport(42L, "test").getId());
        }

        // History: three jobs, newest first; pages are slices of the whole list.
        Map<String, Object> all = page("/api/jobs?limit=100");
        assertEquals(3, ((Number) all.get("total")).intValue());
        List<Map<String, Object>> every = rows(all, "jobs");
        assertEquals(3, every.size());
        assertTrue(((Number) every.get(0).get("id")).longValue() > ((Number) every.get(2).get("id")).longValue());
        Map<String, Object> second = page("/api/jobs?offset=1&limit=1");
        assertEquals(3, ((Number) second.get("total")).intValue());
        assertEquals(1, ((Number) second.get("offset")).intValue());
        assertEquals(List.of(every.get(1)), rows(second, "jobs"));
        assertEquals(List.of(), rows(page("/api/jobs?offset=3&limit=2"), "jobs"));

        // Out-of-range values are kept sensible: offset at least 0, limit between 1 and 100.
        Map<String, Object> clamped = page("/api/jobs?offset=-5&limit=0");
        assertEquals(0, ((Number) clamped.get("offset")).intValue());
        assertEquals(1, rows(clamped, "jobs").size());
        assertEquals(3, rows(page("/api/jobs?limit=5000"), "jobs").size());

        // A search is paged too, with its own total.
        Map<String, Object> searched = page("/api/jobs?q=held&offset=2&limit=2");
        assertEquals(3, ((Number) searched.get("total")).intValue());
        assertEquals(List.of(every.get(2)), rows(searched, "jobs"));
        assertEquals(0, ((Number) page("/api/jobs?q=zzz").get("total")).intValue());

        // Quarantine: every export holds the same records; pages are slices of the whole list.
        Map<String, Object> held = page("/api/quarantine?limit=100");
        List<Map<String, Object>> records = rows(held, "records");
        int total = ((Number) held.get("total")).intValue();
        assertEquals(records.size(), total);
        assertTrue(total >= 3, "each export holds at least one record");
        assertEquals(records.subList(1, 3), rows(page("/api/quarantine?offset=1&limit=2"), "records"));
        Map<String, Object> searchedHeld = page("/api/quarantine?q=shine&limit=1");
        assertEquals(1, rows(searchedHeld, "records").size());
        assertTrue(((Number) searchedHeld.get("total")).intValue() >= 3);

        // With no limit given, a page holds 25 jobs.
        assertEquals(25, defaultLimitCheck());
    }

    /** The page size when none is given. */
    private int defaultLimitCheck() throws Exception {
        for (int run = 0; run < 23; run++) {
            ExportJob job = new ExportJob();
            job.setVersionId(42L);
            job.setListName("Paging");
            job.setStatus("COMPLETED");
            job.setTriggeredBy("paging");
            jobs.save(job);
        }
        Map<String, Object> first = page("/api/jobs");
        assertEquals(26, ((Number) first.get("total")).intValue());
        return rows(first, "jobs").size();
    }

    private Map<String, Object> page(String url) throws Exception {
        String body = mockMvc.perform(get(url).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page, String key) {
        return (List<Map<String, Object>>) page.get(key);
    }

    private void await(Long id) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (List.of("COMPLETED", "FAILED", "HELD").contains(jobs.findById(id).orElseThrow().getStatus())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Export job " + id + " did not finish");
    }
}
