package com.bnpp.export;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo subscribers, and the settings of a subscriber: format, scope, mode, business line and region. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:subscriber_settings;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.sql.init.schema-locations=classpath:db/postgres/ver-export-schema.sql,classpath:group-tables.sql",
        "export.demo-data=true"
})
@AutoConfigureMockMvc
class SubscriberSettingsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sixDemoSubscribersWithTheirSettingsAreThereAtStart() throws Exception {
        String all = asMaker(get("/api/subscribers"));
        assertEquals(List.of("SUN", "SHINE", "Vigilance", "Actimize", "NetReveal", "Compliance Team"), JsonPath.read(all, "$[0:6].name"));
        assertEquals(List.of("ALL", "SANCTIONS", "WORLDCHECK", "PFA", "SANCTIONS", "ALL"), JsonPath.read(all, "$[0:6].scope"));
        assertEquals(List.of("FULL", "DELTA", "FULL", "FULL", "FULL", "FULL"), JsonPath.read(all, "$[0:6].exportMode"));
        assertEquals("CIB", JsonPath.read(all, "$[0].businessLine"));
        assertEquals("EMEA", JsonPath.read(all, "$[0].region"));
        assertEquals("XML", JsonPath.read(all, "$[0].formatType"));
        assertEquals("http://cft.local/inbox/compliance-team", JsonPath.read(all, "$[5].cftEndpoint"));
        assertEquals(List.of("XML"), JsonPath.read(asMaker(get("/api/subscribers/formats")), "$"));
        assertEquals(List.of("SHINE"), JsonPath.read(asMaker(get("/api/subscribers?q=apac")), "$[*].name"), "search covers the region");
    }

    @Test
    void settingsAreSavedValidatedAndKeptOnUpdate() throws Exception {
        String list = asMaker(post("/api/format-templates").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Settings list","fields":[{"sourceTable":"tg_person","sourceColumn":"birth_year","headerName":"birth_year"}]}"""));
        Number id = JsonPath.read(list, "$.id");
        Number family = JsonPath.read(list, "$.familyId");
        mockMvc.perform(post("/api/format-templates/" + id + "/approve").with(user("approver").roles("APPROVER"))).andExpect(status().isOk());

        String created = asMaker(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Fresh","formatType":"xml","scope":"pfa","exportMode":"delta","businessLine":" Retail ","region":"APAC","templateId":%d}""".formatted(family.longValue())));
        assertEquals("XML", JsonPath.read(created, "$.formatType"));
        assertEquals("PFA", JsonPath.read(created, "$.scope"));
        assertEquals("DELTA", JsonPath.read(created, "$.exportMode"));
        assertEquals("Retail", JsonPath.read(created, "$.businessLine"), "trimmed");
        Number subscriber = JsonPath.read(created, "$.id");

        String plain = asMaker(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Plain","templateId":%d}""".formatted(family.longValue())));
        assertEquals(List.of("XML", "ALL", "FULL"), List.of(JsonPath.read(plain, "$.formatType"), JsonPath.read(plain, "$.scope"), JsonPath.read(plain, "$.exportMode")),
                "what is not given gets the default");

        for (String bad : List.of("\"formatType\":\"CSV\"", "\"scope\":\"EVERYTHING\"", "\"exportMode\":\"SOMETIMES\"")) {
            mockMvc.perform(post("/api/subscribers").with(user("maker").roles("MAKER")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Bad\"," + bad + ",\"templateId\":" + family + "}")).andExpect(status().isBadRequest());
        }

        String updated = asMaker(put("/api/subscribers/" + subscriber).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Fresh\",\"scope\":\"ALL\",\"region\":\"EMEA\",\"templateId\":" + family + "}"));
        assertEquals("ALL", JsonPath.read(updated, "$.scope"));
        assertEquals("DELTA", JsonPath.read(updated, "$.exportMode"), "a mode that is not sent stays as it was");
        assertEquals("EMEA", JsonPath.read(updated, "$.region"));
    }

    private String asMaker(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user("maker").roles("MAKER"))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
