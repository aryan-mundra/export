package com.bnpp.export;

import com.bnpp.export.model.ExportJob;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.ExportOrchestrator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.hamcrest.Matchers.greaterThan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "export.storage.directory=target/export-test")
@AutoConfigureMockMvc
class ExportModuleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExportOrchestrator orchestrator;

    @Autowired
    private ExportJobRepository exportJobRepository;

    @Autowired
    private QuarantineRepository quarantineRepository;

    @Autowired
    private SubscriberRepository subscriberRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Test
    void servesTheModuleAndSeedsSubscribers() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized());

        MvcResult makerLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"maker\",\"password\":\"Regliss-maker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MAKER"))
                .andExpect(jsonPath("$.displayName").value("List operator"))
                .andReturn();
        MockHttpSession maker = (MockHttpSession) makerLogin.getRequest().getSession();

        mockMvc.perform(get("/api/dashboard").session(maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubscribers").value(6))
                .andExpect(jsonPath("$.activeSubscribers").value(6));

        mockMvc.perform(get("/api/subscribers").session(maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].name").value("SUN"))
                .andExpect(jsonPath("$[0].formatType").value("FIRCOSOFT"))
                .andExpect(jsonPath("$[0].businessLine").value("CIB"))
                .andExpect(jsonPath("$[0].region").value("EMEA"));

        mockMvc.perform(get("/api/format-templates").session(maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].formatType").value("CSV"))
                .andExpect(jsonPath("$[0].versionNumber").value(1))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].fields", hasSize(3)));

        MvcResult created = mockMvc.perform(post("/api/format-templates")
                        .session(maker)
                        .contentType("application/json")
                        .content("""
                                {"name":"CSV Export","formatType":"CSV","fields":[
                                  {"sourceColumn":"id","headerName":"id"},
                                  {"sourceColumn":"action","headerName":"action"},
                                  {"sourceColumn":"record_date","headerName":"record_date"},
                                  {"sourceColumn":"name","headerName":"name"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.createdByName").value("List operator"))
                .andExpect(jsonPath("$.fields", hasSize(4)))
                .andExpect(jsonPath("$.fields[3].sourceTable").value("List record"))
                .andReturn();
        Number templateId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/format-templates/" + templateId + "/approve").session(maker))
                .andExpect(status().isForbidden());

        MvcResult approverLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"approver\",\"password\":\"Regliss-approver\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("APPROVER"))
                .andReturn();
        MockHttpSession approver = (MockHttpSession) approverLogin.getRequest().getSession();

        mockMvc.perform(post("/api/format-templates/" + templateId + "/approve").session(approver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.approvedBy").value("approver"));

        mockMvc.perform(get("/api/format-templates").session(maker))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].versionNumber").value(1))
                .andExpect(jsonPath("$[1].active").value(false));

        mockMvc.perform(post("/api/format-templates")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"Fircosoft\",\"formatType\":\"FIRCOSOFT\",\"fields\":[{\"sourceColumn\":\"id\",\"headerName\":\"id\"}]}"))
                .andExpect(status().isBadRequest());

        MvcResult channel = mockMvc.perform(post("/api/subscribers")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"New Channel\",\"formatType\":\"CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"cftEndpoint\":\"http://cft.local/inbox/new-channel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.endpointStatus").value("PENDING"))
                .andReturn();
        Number channelId = com.jayway.jsonpath.JsonPath.read(channel.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(put("/api/subscribers/" + channelId)
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"New Channel\",\"formatType\":\"CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"cftEndpoint\":\"http://cft.local/inbox/new-channel\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.endpointStatus").value("PENDING"));

        mockMvc.perform(get("/api/versions").session(maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].versionId").value(42))
                .andExpect(jsonPath("$[2].versionId").value(44));

        mockMvc.perform(get("/api/system").session(maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("h2"));
    }

    @Test
    void exportWritesFilesAndQuarantinesInvalidRows() throws Exception {
        ExportJob job = orchestrator.triggerExport(42L, "test");
        ExportJob finished = await(job.getId());

        assertEquals("HELD", finished.getStatus());
        assertEquals(6, finished.getTotalSubscribers());
        assertEquals(2, finished.getSkippedSubscribers());
        assertTrue(finished.getHeldSubscribers() > 0);
        assertTrue(finished.getTotalRecordsProcessed() > 0);
        assertTrue(finished.getTotalRecordsQuarantined() > 0);
        int sentBeforeAccept = deliveryRepository.findByVersionIdOrderByCreatedAtDesc(42L).size();
        assertTrue(sentBeforeAccept >= 1);
        assertTrue(quarantineRepository.count() > 0);

        long jobsBefore = exportJobRepository.count();
        long quarantineBefore = quarantineRepository.count();
        mockMvc.perform(post("/api/exports/preview")
                        .with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listName").value("Sanctions"))
                .andExpect(jsonPath("$.subscribers", hasSize(6)))
                .andExpect(jsonPath("$.subscribers[2].name").value("Vigilance"))
                .andExpect(jsonPath("$.subscribers[2].included").value(false));
        assertEquals(jobsBefore, exportJobRepository.count());
        assertEquals(quarantineBefore, quarantineRepository.count());

        mockMvc.perform(post("/api/exports")
                        .with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":999}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/exports")
                        .with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":42,\"triggeredBy\":\"not-the-signed-in-user\"}"))
                .andExpect(status().isConflict());

        var held = orchestrator.getJob(job.getId()).subscribers().stream()
                .filter(row -> "HELD".equals(row.status()) && row.fileName() != null)
                .findFirst()
                .orElseThrow();
        mockMvc.perform(post("/api/jobs/" + job.getId() + "/subscribers/" + held.id() + "/accept")
                        .with(user("maker").roles("MAKER")))
                .andExpect(status().isOk());
        assertEquals(sentBeforeAccept + 1, deliveryRepository.findByVersionIdOrderByCreatedAtDesc(42L).size());
        mockMvc.perform(post("/api/jobs/" + job.getId() + "/subscribers/" + held.id() + "/proof")
                        .with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"));

        mockMvc.perform(get("/api/jobs/" + job.getId()).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers", hasSize(6)))
                .andExpect(jsonPath("$.listName").value("Sanctions"));

        mockMvc.perform(get("/api/versions/42/report").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD"));

        mockMvc.perform(post("/api/subscribers")
                        .with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"name\":\" \",\"formatType\":\"CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\"}"))
                .andExpect(status().isBadRequest());

        Long sunId = subscriberRepository.findByNameIgnoreCase("SUN").orElseThrow().getId();
        mockMvc.perform(delete("/api/subscribers/" + sunId).with(user("maker").roles("MAKER")))
                .andExpect(status().isConflict());

        MvcResult worldCheck = mockMvc.perform(post("/api/exports/preview")
                        .with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":43}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(worldCheck.getResponse().getContentAsString(), "$.reviewToken");
        mockMvc.perform(post("/api/exports")
                        .with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":43,\"triggeredBy\":\"not-the-signed-in-user\",\"reviewToken\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggeredBy").value("maker"));
    }

    @Test
    void dateWindowLimitsDeltaSubscribersAndLeavesFullOnesWhole() throws Exception {
        mockMvc.perform(post("/api/exports")
                        .with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("{\"versionId\":42,\"fromDate\":\"2026-03-10\",\"toDate\":\"2026-03-01\"}"))
                .andExpect(status().isBadRequest());

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        ExportJob job = orchestrator.triggerExport(77L, "test", tomorrow, tomorrow);
        ExportJob finished = await(job.getId());

        assertEquals("HELD", finished.getStatus());
        assertEquals(tomorrow, finished.getFromDate());
        assertEquals(tomorrow, finished.getToDate());
        mockMvc.perform(get("/api/jobs/" + job.getId()).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers[0].subscriberName").value("SUN"))
                .andExpect(jsonPath("$.subscribers[0].recordsProcessed").value(greaterThan(0)))
                .andExpect(jsonPath("$.subscribers[1].subscriberName").value("SHINE"))
                .andExpect(jsonPath("$.subscribers[1].recordsProcessed").value(0));
    }

    private ExportJob await(Long id) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ExportJob job = exportJobRepository.findById(id).orElseThrow();
            if ("COMPLETED".equals(job.getStatus()) || "FAILED".equals(job.getStatus()) || "HELD".equals(job.getStatus())) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Export job " + id + " did not finish");
    }
}
