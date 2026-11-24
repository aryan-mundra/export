package com.bnpp.export;

import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.ExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "export.storage.directory=target/export-test")
@AutoConfigureMockMvc
class ExportModuleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExportService orchestrator;

    @Autowired
    private ExportJobRepository exportJobRepository;

    @Autowired
    private QuarantineRepository quarantineRepository;

    @Autowired
    private SubscriberRepository subscriberRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private com.bnpp.export.repository.ExportJobSubscriberStatusRepository statusRepository;

    @Autowired
    private com.bnpp.export.repository.ExportedFileRepository exportedFileRepository;

    @Autowired
    private com.bnpp.export.repository.ExportedFileRecordRepository exportedFileRecordRepository;

    @Autowired
    private com.bnpp.export.repository.ExportAuditLogRepository auditLogRepository;

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
                .andExpect(jsonPath("$.displayName").value("List Manager"))
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
                .andExpect(jsonPath("$[0].formatType").value("XML"))
                .andExpect(jsonPath("$[0].businessLine").value("CIB"))
                .andExpect(jsonPath("$[0].region").value("EMEA"));

        mockMvc.perform(get("/api/format-templates").session(maker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNumber").value(1))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].fields", hasSize(3)))
                // Demo templates are built from gs_ columns only
                .andExpect(jsonPath("$[0].fields[*].sourceTable", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("gs_party"))));

        // A file holds only gs_ columns: the list entry's own fields cannot be picked.
        mockMvc.perform(post("/api/format-templates")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"formatType\":\"CSV\",\"fields\":[{\"sourceColumn\":\"action\",\"headerName\":\"action\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick columns from gs_ tables. \"action\" is not a gs_ column."));

        MvcResult created = mockMvc.perform(post("/api/format-templates")
                        .session(maker)
                        .contentType("application/json")
                        .content("""
                                {"name":"Party Basic","formatType":"CSV","fields":[
                                  {"sourceTable":"GS_PARTY","sourceColumn":"BNP_ID","headerName":"id"},
                                  {"sourceTable":"GS_PARTY","sourceColumn":"FULL_NAME","headerName":"name"},
                                  {"sourceTable":"GS_PARTY","sourceColumn":"NATIONALITY","headerName":"nationality"},
                                  {"sourceTable":"GS_PARTY","sourceColumn":"PARTY_TYPE","headerName":"party_type"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.createdByName").value("List Manager"))
                .andExpect(jsonPath("$.fields", hasSize(4)))
                .andExpect(jsonPath("$.fields[3].sourceTable").value("gs_party"))
                .andExpect(jsonPath("$.fields[3].joinColumn").value("bnp_id"))
                .andExpect(jsonPath("$.fields[3].joinField").value("bnp_id"))
                .andReturn();
        Number templateId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        Number csvFamilyId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.familyId");

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

        // A list has no file format: column names are free text, and XML files turn them into elements.
        org.junit.jupiter.api.Assertions.assertEquals("Country_risk", com.bnpp.export.format.XmlFormatGenerator.elementName("Country risk"));
        org.junit.jupiter.api.Assertions.assertEquals("_2nd_name", com.bnpp.export.format.XmlFormatGenerator.elementName("2nd name"));
        org.junit.jupiter.api.Assertions.assertEquals("_xmlData", com.bnpp.export.format.XmlFormatGenerator.elementName("xmlData"));
        // Two names that would be the same XML element are refused.
        mockMvc.perform(post("/api/format-templates")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"fields\":[{\"sourceTable\":\"GS_PARTY\",\"sourceColumn\":\"FULL_NAME\",\"headerName\":\"party name\"},"
                                + "{\"sourceTable\":\"GS_PARTY\",\"sourceColumn\":\"NATIONALITY\",\"headerName\":\"party_name\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("would both be <party_name>")));

        mockMvc.perform(post("/api/subscribers")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"Old Format\",\"formatType\":\"GENERIC_XML\",\"scope\":\"ALL\",\"exportMode\":\"FULL\"}"))
                .andExpect(status().isBadRequest());

        MvcResult both = mockMvc.perform(post("/api/subscribers")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"Both Formats\",\"formatType\":\"xml, CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"templateId\":" + csvFamilyId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formatType").value("CSV,XML"))
                .andReturn();
        Number bothId = com.jayway.jsonpath.JsonPath.read(both.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(delete("/api/subscribers/" + bothId).session(maker)).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/subscribers")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"Bad Mix\",\"formatType\":\"CSV,PDF\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"templateId\":" + csvFamilyId + "}"))
                .andExpect(status().isBadRequest());

        MvcResult channel = mockMvc.perform(post("/api/subscribers")
                        .session(maker)
                        .contentType("application/json")
                        .content("{\"name\":\"New Channel\",\"formatType\":\"CSV\",\"scope\":\"ALL\",\"exportMode\":\"FULL\",\"cftEndpoint\":\"http://cft.local/inbox/new-channel\",\"templateId\":" + csvFamilyId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateName").value("Party Basic"))
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
                .andExpect(jsonPath("$.mode").value("h2"))
                .andExpect(jsonPath("$.postgresPassword").doesNotExist());

        // The Database switch restarts the module, which a test context cannot do: it says so and changes nothing.
        mockMvc.perform(post("/api/system/database").session(maker)
                        .contentType("application/json")
                        .content("{\"mode\":\"postgres\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("cannot be switched")));
    }

    /**
     * Every active subscriber gets a file, whatever list the version belongs to: the subscriber's
     * scope filters the records inside it. The demo data has no blank fields, so nothing is held.
     * Quarantine, accepting omissions and the delivery proof on a held file are covered by
     * PostgresExportFilesTest, whose data has blank fields.
     */
    @Test
    void exportWritesAFileForEverySubscriber() throws Exception {
        ExportJob job = orchestrator.triggerExport(42L, "test");
        ExportJob finished = await(job.getId());

        assertEquals("COMPLETED", finished.getStatus());
        assertEquals(6, finished.getTotalSubscribers());
        assertEquals(6, finished.getCompletedSubscribers());
        assertEquals(0, finished.getSkippedSubscribers());
        assertEquals(0, finished.getHeldSubscribers());
        assertEquals(0, finished.getFailedSubscribers());
        assertEquals(0, finished.getTotalRecordsQuarantined());
        assertTrue(finished.getTotalRecordsProcessed() > 0);

        var rows = orchestrator.getJob(job.getId()).subscribers();
        for (var row : rows) {
            assertEquals("COMPLETED", row.status(), row.subscriberName());
            if (row.recordsProcessed() > 0) {
                assertTrue(row.fileName() != null && row.archiveFileName() != null, row.subscriberName() + " file was not sent");
            }
        }
        var sent = rows.stream().filter(row -> row.archiveFileName() != null).findFirst().orElseThrow();
        mockMvc.perform(post("/api/jobs/" + job.getId() + "/subscribers/" + sent.id() + "/proof")
                        .with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"));

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
                .andExpect(jsonPath("$.subscribers[2].scope").value("WORLDCHECK"))
                .andExpect(jsonPath("$.subscribers[2].included").value(true));
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

        mockMvc.perform(get("/api/jobs/" + job.getId()).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers", hasSize(6)))
                .andExpect(jsonPath("$.listName").value("Sanctions"));

        mockMvc.perform(get("/api/versions/42/report").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

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

        // No record changed tomorrow, so the delta subscriber gets an empty window; full ones are unaffected.
        assertEquals("COMPLETED", finished.getStatus());
        assertEquals(tomorrow, finished.getFromDate());
        assertEquals(tomorrow, finished.getToDate());
        mockMvc.perform(get("/api/jobs/" + job.getId()).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers[0].subscriberName").value("SUN"))
                .andExpect(jsonPath("$.subscribers[0].recordsProcessed").value(greaterThan(0)))
                .andExpect(jsonPath("$.subscribers[1].subscriberName").value("SHINE"))
                .andExpect(jsonPath("$.subscribers[1].recordsProcessed").value(0))
                .andExpect(jsonPath("$.subscribers[1].holdReason").value("Empty delta window. Nothing was sent."));
    }

    @Test
    void templatesPickColumnsFromGoldenSourceTables() throws Exception {
        mockMvc.perform(get("/api/format-templates/sources").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].label", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("List record"))))
                .andExpect(jsonPath("$[*].table", org.hamcrest.Matchers.hasItems(
                        "gs_party", "gs_address", "Gs_Identifier", "gs_sanction_program", "gs_load_log")))
                .andExpect(jsonPath("$[*].table", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ref_country"))))
                // Every usable gs_ table has BNP_ID; GS_LOAD_LOG has none and is locked
                .andExpect(jsonPath("$[?(@.table == 'gs_load_log')].keyColumn").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$[?(@.table == 'gs_load_log')].joinable").value(false))
                .andExpect(jsonPath("$[?(@.table == 'gs_country_risk')].keyColumn").value("bnp_id"))
                .andExpect(jsonPath("$[?(@.table == 'gs_address')].keyColumn").value("bnp_id"))
                .andExpect(jsonPath("$[?(@.table == 'gs_party')].keyColumn").value("bnp_id"));

        String badTable = "{\"name\":\"X\",\"formatType\":\"XML\",\"fields\":[{\"sourceTable\":\"REF_COUNTRY\",\"sourceColumn\":\"NAME\",\"headerName\":\"n\"}]}";
        mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json").content(badTable))
                .andExpect(status().isBadRequest());
        // A gs_ table without BNP_ID cannot be used
        String noKey = "{\"name\":\"X\",\"formatType\":\"XML\",\"fields\":[{\"sourceTable\":\"GS_LOAD_LOG\",\"sourceColumn\":\"ROW_COUNT\",\"headerName\":\"n\"}]}";
        mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json").content(noKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("gs_load_log has no BNP_ID column, so its rows cannot be matched to list entries"));
        String duplicateHeader = """
                {"name":"X","formatType":"XML","fields":[
                  {"sourceTable":"GS_PARTY","sourceColumn":"BNP_ID","headerName":"same"},
                  {"sourceTable":"GS_PARTY","sourceColumn":"FULL_NAME","headerName":"Same"}]}
                """;
        mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json").content(duplicateHeader))
                .andExpect(status().isBadRequest());

        // The data explorer shows the rows the template would put in a file
        mockMvc.perform(post("/api/format-templates/explore").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("""
                                {"fields":[
                                  {"sourceTable":"GS_PARTY","sourceColumn":"BNP_ID","headerName":""},
                                  {"sourceTable":"gs_party","sourceColumn":"full_name","headerName":""}
                                ],"limit":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(42))
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].cells[0]").value("BNP42000001"))
                .andExpect(jsonPath("$.rows[0].cells[1]").value("Party 42000001"));

        MvcResult created = mockMvc.perform(post("/api/format-templates").with(user("maker").roles("MAKER"))
                        .contentType("application/json")
                        .content("""
                                {"name":"XML with golden source","formatType":"XML","fields":[
                                  {"sourceTable":"GS_PARTY","sourceColumn":"BNP_ID","headerName":"id"},
                                  {"sourceTable":"gs_party","sourceColumn":"full_name","headerName":"PartyName"},
                                  {"sourceTable":"GS_ADDRESS","sourceColumn":"city","headerName":"city"},
                                  {"sourceTable":"gs_identifier","sourceColumn":"id_value","headerName":"identifiers"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[1].sourceTable").value("gs_party"))
                .andExpect(jsonPath("$.fields[1].sourceColumn").value("full_name"))
                .andExpect(jsonPath("$.fields[1].headerName").value("PartyName"))
                .andExpect(jsonPath("$.fields[2].sourceTable").value("gs_address"))
                .andExpect(jsonPath("$.fields[3].sourceTable").value("Gs_Identifier"))
                .andReturn();
        Number templateId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        Number familyId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.familyId");
        var shineProfile = subscriberRepository.findByNameIgnoreCase("SHINE").orElseThrow();
        String chooseNew = "{\"name\":\"SHINE\",\"formatType\":\"XML\",\"scope\":\"" + shineProfile.getScope()
                + "\",\"exportMode\":\"" + shineProfile.getExportMode() + "\",\"businessLine\":\"" + shineProfile.getBusinessLine()
                + "\",\"region\":\"" + shineProfile.getRegion() + "\",\"cftEndpoint\":\"" + shineProfile.getCftEndpoint()
                + "\",\"templateId\":" + familyId + "}";
        // A template cannot be chosen before an approver has approved a version of it...
        mockMvc.perform(put("/api/subscribers/" + shineProfile.getId()).with(user("maker").roles("MAKER"))
                        .contentType("application/json").content(chooseNew))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("has no approved version yet")));
        mockMvc.perform(post("/api/format-templates/" + templateId + "/approve").with(user("approver").roles("APPROVER")))
                .andExpect(status().isOk());
        // SHINE now uses the new template; every other XML subscriber keeps "Party Detail".
        mockMvc.perform(put("/api/subscribers/" + shineProfile.getId()).with(user("maker").roles("MAKER"))
                        .contentType("application/json").content(chooseNew))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateName").value("XML with golden source"));

        ExportJob job = await(orchestrator.triggerExport(43L, "test").getId());
        var sun = orchestrator.getJob(job.getId()).subscribers().stream()
                .filter(row -> "SUN".equals(row.subscriberName())).findFirst().orElseThrow();
        assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of(sun.filePath())).contains("<BnpId>"),
                "SUN keeps its own template");
        var shine = orchestrator.getJob(job.getId()).subscribers().stream()
                .filter(row -> "SHINE".equals(row.subscriberName()))
                .findFirst()
                .orElseThrow();
        String xml = java.nio.file.Files.readString(java.nio.file.Path.of(shine.filePath()));
        java.util.regex.Matcher record = java.util.regex.Pattern
                .compile("<Record id=\"(\\d+)\">\\s*<id>BNP\\1</id>\\s*<PartyName>Party \\1</PartyName>\\s*<city>[^<]+</city>\\s*<identifiers>ID\\1")
                .matcher(xml);
        assertTrue(record.find(), "golden-source values should be joined on the record id:\n" + xml);

        // ver_export_file has the file, and ver_export_file_record lists its records in file order
        var file = exportedFileRepository.findFirstByFilePathOrderByIdDesc(shine.filePath()).orElseThrow();
        assertEquals("XML", file.getFormatType());
        assertEquals(shine.id(), file.getJobSubscriberId());
        assertEquals(64, file.getFileHash().length());
        var lines = exportedFileRecordRepository.findByFileIdOrderByLineNumberAsc(file.getId());
        assertEquals(file.getRecordCount(), lines.size());
        assertEquals(shine.recordsProcessed(), lines.size());
        assertEquals(record.group(1), lines.get(0).getRecordId());
        assertEquals(1, lines.get(0).getLineNumber());
        if ("COMPLETED".equals(shine.status())) {
            assertEquals("SENT", file.getStatus());
        }

        // ver_export_audit names who submitted and who approved the template
        var templateAudit = auditLogRepository.findAll().stream()
                .filter(entry -> "TEMPLATE".equals(entry.getEntityType()) && templateId.longValue() == entry.getEntityId())
                .toList();
        assertEquals(java.util.List.of("TEMPLATE_SUBMITTED", "TEMPLATE_APPROVED"),
                templateAudit.stream().map(entry -> entry.getAction()).toList());
        assertEquals(java.util.List.of("maker", "approver"),
                templateAudit.stream().map(entry -> entry.getActor()).toList());
    }

    /** A job that was running when the application stopped must end FAILED (retryable), not stay "In progress". */
    @Test
    void interruptedExportsAreMarkedFailedAtStartUp() {
        ExportJob job = new ExportJob();
        job.setVersionId(42L);
        job.setListCode("SANCTIONS");
        job.setListName("Sanctions");
        job.setStatus("IN_PROGRESS");
        job.setTotalSubscribers(1);
        job = exportJobRepository.save(job);
        com.bnpp.export.entity.ExportJobSubscriberStatus running = new com.bnpp.export.entity.ExportJobSubscriberStatus();
        running.setExportJobId(job.getId());
        running.setSubscriberId(subscriberRepository.findByNameIgnoreCase("SUN").orElseThrow().getId());
        running.setSubscriberName("SUN");
        running.setFormatType("XML");
        running.setStatus("IN_PROGRESS");
        statusRepository.save(running);

        assertTrue(orchestrator.recoverInterruptedExports() >= 1);

        ExportJob recovered = exportJobRepository.findById(job.getId()).orElseThrow();
        assertEquals("FAILED", recovered.getStatus());
        assertTrue(recovered.getErrorMessage().startsWith("Interrupted"));
        var row = orchestrator.getJob(job.getId()).subscribers().get(0);
        assertEquals("FAILED", row.status());
        assertEquals(1, recovered.getFailedSubscribers());
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
