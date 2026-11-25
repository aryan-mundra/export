package com.bnpp.export;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole flow on table groups (the groups are discovered from the foreign keys of group-tables.sql): the group tables
 * offered, a preview per group, saving and approving a list, its XML, data versions, Synthesis between two versions,
 * a subscriber, and sending a version to it (file delivered, archived, in History).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:group_flow;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.sql.init.schema-locations=classpath:db/postgres/ver-export-schema.sql,classpath:group-tables.sql"
})
@AutoConfigureMockMvc
class GroupExportFlowTest {

    private static final String FIELDS = """
            [{"sourceTable":"tg_person","sourceColumn":"birth_year","headerName":"birth_year"},
             {"sourceTable":"tg_person_name","sourceColumn":"first_name","headerName":"first_name"},
             {"sourceTable":"tg_person_address","sourceColumn":"city","headerName":"city"},
             {"sourceTable":"tg_address_country","sourceColumn":"country","headerName":"country"},
             {"sourceTable":"tg_entity_code","sourceColumn":"code","headerName":"code"}]""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void groupsFromForeignKeysToDeliveredFile() throws Exception {
        // ---- The groups discovered from the foreign keys: one per root table (by name), related tables level by level.
        List<Map<String, Object>> tables = JsonPath.read(asMaker(get("/api/format-templates/tables")), "$");
        assertEquals(List.of("tg_entity", "tg_entity_code", "tg_person", "tg_person_address", "tg_person_name", "tg_address_country"),
                tables.stream().map(t -> t.get("table")).toList(), "2 tables of tg_entity and 4 of tg_person; no ver_export_ table");
        assertEquals(List.of("tg_entity", "tg_entity", "tg_person", "tg_person", "tg_person", "tg_person"), tables.stream().map(t -> t.get("group")).toList());
        assertEquals(true, tables.get(2).get("root"));
        assertEquals("person_id", tables.get(2).get("groupKey"));
        assertEquals("addr_fk", tables.get(5).get("keyColumn"));
        assertEquals("tg_person_address.address_no", tables.get(5).get("joinsTo"));
        assertEquals("ent_ref", tables.get(1).get("keyColumn"));

        // ---- One preview per group: the key, and related values "; "-separated.
        String preview = asMaker(post("/api/format-templates/preview").contentType(MediaType.APPLICATION_JSON).content("{\"fields\":" + FIELDS + "}"));
        assertEquals(List.of("tg_entity", "tg_person"), JsonPath.read(preview, "$[*].group"));
        assertEquals(2, ((Number) JsonPath.read(preview, "$[1].total")).intValue());
        assertEquals(List.of("1970", "John; Johnny", "London; Paris", "GB; FR"), JsonPath.read(preview, "$[1].rows[0].values"));
        assertEquals(List.of("ISIN1; ISIN2"), JsonPath.read(preview, "$[0].rows[0].values"));

        // ---- Saving adds each group's key; only a List Owner approves, with a reason for sending back.
        String saved = asMaker(post("/api/format-templates").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Groups list\",\"fields\":" + FIELDS + "}"));
        assertEquals(List.of("person_id", "birth_year", "first_name", "city", "country", "entity_id", "code"),
                JsonPath.read(saved, "$.fields[*].headerName"));
        Number listVersion = JsonPath.read(saved, "$.id");
        Number family = JsonPath.read(saved, "$.familyId");
        mockMvc.perform(post("/api/format-templates/" + listVersion + "/approve").with(user("maker").roles("MAKER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/format-templates/" + listVersion + "/request-changes").with(user("approver").roles("APPROVER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"short\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/format-templates/" + listVersion + "/approve").with(user("approver").roles("APPROVER"))).andExpect(status().isOk());

        // ---- The list's XML: groups by root table name, nested by foreign key, key as attribute.
        String xml = download(get("/api/format-templates/" + listVersion + "/xml"));
        assertTrue(xml.contains("""
                    <tg_person person_id="1">
                      <birth_year>1970</birth_year>
                      <tg_person_addresses>
                        <tg_person_address>
                          <city>London</city>
                          <tg_address_countries>
                            <tg_address_country>
                              <country>GB</country>
                            </tg_address_country>
                          </tg_address_countries>
                        </tg_person_address>
                        <tg_person_address>
                          <city>Paris</city>
                          <tg_address_countries>
                            <tg_address_country>
                              <country>FR</country>
                            </tg_address_country>
                          </tg_address_countries>
                        </tg_person_address>
                      </tg_person_addresses>
                      <tg_person_names>
                        <tg_person_name>
                          <first_name>John</first_name>
                        </tg_person_name>
                        <tg_person_name>
                          <first_name>Johnny</first_name>
                        </tg_person_name>
                      </tg_person_names>
                    </tg_person>
                """), xml);
        assertTrue(xml.indexOf("<tg_entities count=\"1\">") < xml.indexOf("<tg_persons count=\"2\">"), xml);

        // ---- Data versions: unchanged data reuses the newest version.
        String v1 = asMaker(post("/api/data-versions?familyId=" + family));
        assertEquals(true, JsonPath.read(v1, "$.created"));
        assertEquals(2, ((Number) JsonPath.read(v1, "$.version.groups.tg_person")).intValue());
        Number first = JsonPath.read(v1, "$.version.id");
        assertEquals(false, JsonPath.read(asMaker(post("/api/data-versions?familyId=" + family)), "$.created"));

        jdbc.execute("UPDATE tg_person_address SET city = 'Lyon' WHERE address_no = 11");  // a row with an id changes
        jdbc.execute("DELETE FROM tg_person_name WHERE name_id = 2");                     // Johnny removed
        jdbc.execute("INSERT INTO tg_person_name VALUES (4, 2, 'Mary')");                 // a name added to person 2
        jdbc.execute("UPDATE tg_person SET birth_year = 1971 WHERE person_id = 1");       // a root value changes
        jdbc.execute("INSERT INTO tg_person VALUES (3, 1990)");                           // a new person
        String v2 = asMaker(post("/api/data-versions?familyId=" + family));
        assertEquals(2, ((Number) JsonPath.read(v2, "$.version.number")).intValue());
        Number second = JsonPath.read(v2, "$.version.id");

        // ---- Synthesis between the two versions.
        String base = "/api/data-versions/" + second + "/compare/" + first;
        String summary = asMaker(get(base + "/summary"));
        assertEquals(List.of(1, 0, 2, 0), List.of(JsonPath.read(summary, "$.groups[1].added"), JsonPath.read(summary, "$.groups[1].removed"),
                JsonPath.read(summary, "$.groups[1].changed"), JsonPath.read(summary, "$.groups[1].unchanged")));
        assertEquals(1, ((Number) JsonPath.read(summary, "$.groups[0].unchanged")).intValue());
        assertEquals(List.of("tg_person", "tg_person_address", "tg_person_name", "tg_address_country"), JsonPath.read(summary, "$.groups[1].tables[*].table"));
        assertEquals(List.of(1, 1, 2, 0), JsonPath.read(summary, "$.groups[1].tables[*].records"));
        assertEquals(List.of(0, 0, 1, 0), JsonPath.read(summary, "$.groups[1].tables[*].rowsAdded"));
        assertEquals(List.of(0, 0, 1, 0), JsonPath.read(summary, "$.groups[1].tables[*].rowsRemoved"));
        assertEquals(List.of(1, 1, 0, 0), JsonPath.read(summary, "$.groups[1].tables[*].valuesChanged"));
        String records = asMaker(get(base + "/records?group=tg_person"));
        assertEquals(List.of("1", "2", "3"), JsonPath.read(records, "$.records[*].key"));
        assertEquals(List.of("CHANGED", "CHANGED", "ADDED"), JsonPath.read(records, "$.records[*].change"));
        assertEquals("birth_year changed · tg_person_addresses: 1 value changed · tg_person_names: 1 removed", JsonPath.read(records, "$.records[0].summary"));
        assertEquals(1, ((Number) JsonPath.read(asMaker(get(base + "/records?group=tg_person&change=ADDED")), "$.total")).intValue());
        List<Map<String, Object>> differences = JsonPath.read(asMaker(get(base + "/record?group=tg_person&key=1")), "$.differences");
        assertTrue(differences.stream().anyMatch(d -> "tg_person_address 11".equals(d.get("path")) && "Paris".equals(d.get("before"))
                && "Lyon".equals(d.get("after"))), differences.toString());
        assertTrue(differences.stream().anyMatch(d -> "ROW_REMOVED".equals(d.get("kind")) && String.valueOf(d.get("before")).contains("Johnny")),
                differences.toString());
        String stored = download(get("/api/data-versions/" + first + "/xml"));
        assertTrue(stored.contains("<city>Paris</city>") && !stored.contains("Lyon"), "a stored version keeps its data");

        // ---- A subscriber of the list, and sending version 2 to it.
        String subscriber = asMaker(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SUN\",\"templateId\":" + family + "}"));
        Number subscriberId = JsonPath.read(subscriber, "$.id");
        String review = asMaker(post("/api/data-versions/" + second + "/send/review").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertEquals(List.of(subscriberId.intValue()), JsonPath.read(review, "$.defaultSubscribers"), "the list's subscribers by default");
        assertEquals(List.of(4), JsonPath.read(review, "$.subscribers[*].records"), "3 persons and 1 entity");
        String token = JsonPath.read(review, "$.reviewToken");
        mockMvc.perform(post("/api/data-versions/" + second + "/send").with(user("maker").roles("MAKER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subscriberIds\":[" + subscriberId + "],\"reviewToken\":\"stale\"}")).andExpect(status().isConflict());
        Number jobId = JsonPath.read(asMaker(post("/api/data-versions/" + second + "/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subscriberIds\":[" + subscriberId + "],\"reviewToken\":\"" + token + "\"}")), "$.id");
        String job = awaitFinished(jobId);
        assertEquals("COMPLETED", JsonPath.read(job, "$.status"), job);
        assertEquals(2, ((Number) JsonPath.read(job, "$.dataVersionNumber")).intValue());
        assertEquals(List.of("COMPLETED"), JsonPath.read(job, "$.subscribers[*].status"));
        assertEquals(List.of(4), JsonPath.read(job, "$.subscribers[*].records"));
        String archive = JsonPath.read(job, "$.subscribers[0].archiveFileName");
        assertTrue(archive != null && archive.endsWith(".xml"), "the delivered file is archived as proof");
        Number fileId = JsonPath.read(job, "$.subscribers[0].id");
        String sent = mockMvc.perform(get("/api/jobs/" + jobId + "/files/" + fileId).with(user("maker").roles("MAKER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sent.contains("<city>Lyon</city>") && sent.contains("dataVersion=\"2\""), sent);
        assertEquals("MATCH", JsonPath.read(asMaker(post("/api/jobs/" + jobId + "/files/" + fileId + "/proof")), "$.result"));

        // ---- History and dashboard.
        String history = asMaker(get("/api/jobs?q=groups"));
        assertEquals(1, ((Number) JsonPath.read(history, "$.total")).intValue());
        assertEquals(List.of(4), JsonPath.read(history, "$.jobs[*].totalRecords"));
        assertEquals(1, ((Number) JsonPath.read(asMaker(get("/api/dashboard")), "$.successfulJobs")).intValue());
        assertFalse(asMaker(get("/api/subscribers")).contains("\"lastFileName\":null"), "the subscriber shows its last file");
    }

    private String awaitFinished(Number jobId) throws Exception {
        String job = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            job = asMaker(get("/api/jobs/" + jobId));
            if (List.of("COMPLETED", "FAILED").contains(JsonPath.read(job, "$.status"))) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Send " + jobId + " did not finish: " + job);
    }

    private String asMaker(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request.with(user("maker").roles("MAKER"))).andExpect(status().isOk()).andReturn());
    }

    /** A streamed file download (FileResponses.temporary answers asynchronously). */
    private String download(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult started = mockMvc.perform(request.with(user("maker").roles("MAKER"))).andExpect(status().isOk()).andReturn();
        return body(mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started)).andExpect(status().isOk()).andReturn());
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
