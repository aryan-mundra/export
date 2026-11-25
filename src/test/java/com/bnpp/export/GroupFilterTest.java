package com.bnpp.export;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filters on the columns of a list: the choices come from the data; a record whose column holds another value is left out,
 * and so is a record with no related row holding a chosen value (a person with no matching address).
 *
 * <p>Data (group-tables.sql): person 1 has addresses London (GB) and Paris (FR) and names John, Johnny, born 1970;
 * person 2 has no address, one name (Maria) and no birth year. One entity E1 with codes ISIN1 and ISIN2.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:group_filters;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.sql.init.schema-locations=classpath:db/postgres/ver-export-schema.sql,classpath:group-tables.sql"
})
@AutoConfigureMockMvc
class GroupFilterTest {

    @Autowired
    private MockMvc mockMvc;

    private static String fields(String cityValues, String countryValues, String yearValues, String codeValues) {
        return "[{\"sourceTable\":\"tg_person\",\"sourceColumn\":\"birth_year\",\"headerName\":\"birth_year\",\"filterValues\":" + yearValues + "},"
                + "{\"sourceTable\":\"tg_person_name\",\"sourceColumn\":\"first_name\",\"headerName\":\"first_name\"},"
                + "{\"sourceTable\":\"tg_person_address\",\"sourceColumn\":\"city\",\"headerName\":\"city\",\"filterValues\":" + cityValues + "},"
                + "{\"sourceTable\":\"tg_address_country\",\"sourceColumn\":\"country\",\"headerName\":\"country\",\"filterValues\":" + countryValues + "},"
                + "{\"sourceTable\":\"tg_entity_code\",\"sourceColumn\":\"code\",\"headerName\":\"code\",\"filterValues\":" + codeValues + "}]";
    }

    private String preview(String fields) throws Exception {
        return asMaker(post("/api/format-templates/preview").contentType(MediaType.APPLICATION_JSON).content("{\"fields\":" + fields + "}"));
    }

    @Test
    void theChoicesComeFromTheData() throws Exception {
        assertEquals(List.of("London", "Paris"), JsonPath.read(
                asMaker(get("/api/format-templates/column-values?table=tg_person_address&column=city")), "$.values"));
        assertEquals(false, JsonPath.read(asMaker(get("/api/format-templates/column-values?table=tg_person_address&column=city")), "$.truncated"));
        assertEquals(List.of("London"), JsonPath.read(
                asMaker(get("/api/format-templates/column-values?table=tg_person_address&column=city&q=lon")), "$.values"), "search narrows the choices");
        assertEquals(List.of("1970"), JsonPath.read(asMaker(get("/api/format-templates/column-values?table=tg_person&column=birth_year")), "$.values"),
                "numbers are offered as text; null is not a choice");
        mockMvc.perform(get("/api/format-templates/column-values?table=nope&column=x").with(user("maker").roles("MAKER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/format-templates/column-values?table=tg_person&column=nope").with(user("maker").roles("MAKER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRecordWithNoMatchingRelatedRowIsLeftOut() throws Exception {
        String none = preview(fields("[]", "[]", "[]", "[]"));
        assertEquals(List.of("tg_entity", "tg_person"), JsonPath.read(none, "$[*].group"));
        assertEquals(2, ((Number) JsonPath.read(none, "$[1].total")).intValue(), "no filter: both persons");

        // city = Paris: person 2 has no address, so she is left out; person 1 keeps only the Paris address.
        String paris = preview(fields("[\"Paris\"]", "[]", "[]", "[]"));
        assertEquals(1, ((Number) JsonPath.read(paris, "$[1].total")).intValue());
        assertEquals(List.of("1"), JsonPath.read(paris, "$[1].rows[*].key"));
        assertEquals(List.of("1970", "John; Johnny", "Paris", "FR"), JsonPath.read(paris, "$[1].rows[0].values"),
                "only the matching address, with its own country");
        assertEquals(1, ((Number) JsonPath.read(paris, "$[0].total")).intValue(), "the other group is not touched");

        // Several values of one column are an OR.
        assertEquals(1, ((Number) JsonPath.read(preview(fields("[\"Paris\",\"London\"]", "[]", "[]", "[]")), "$[1].total")).intValue());
        assertEquals(0, ((Number) JsonPath.read(preview(fields("[\"Mumbai\"]", "[]", "[]", "[]")), "$[1].total")).intValue(), "a value nobody has");

        // A filter two levels down (country) leaves out the persons, and the addresses, without such a country.
        String fr = preview(fields("[]", "[\"FR\"]", "[]", "[]"));
        assertEquals(1, ((Number) JsonPath.read(fr, "$[1].total")).intValue());
        assertEquals("Paris", ((List<?>) JsonPath.read(fr, "$[1].rows[0].values")).get(2), "the London address has no FR country: left out");

        // Filters on two columns are an AND.
        assertEquals(0, ((Number) JsonPath.read(preview(fields("[\"London\"]", "[\"FR\"]", "[]", "[]")), "$[1].total")).intValue(),
                "London is a GB address");
        assertEquals(1, ((Number) JsonPath.read(preview(fields("[\"London\"]", "[\"GB\"]", "[]", "[]")), "$[1].total")).intValue());

        // A filter on the root's own column; a person without a value never matches.
        assertEquals(1, ((Number) JsonPath.read(preview(fields("[]", "[]", "[\"1970\"]", "[]")), "$[1].total")).intValue());

        // Each group is filtered on its own columns.
        String entity = preview(fields("[]", "[]", "[]", "[\"ISIN2\"]"));
        assertEquals(2, ((Number) JsonPath.read(entity, "$[1].total")).intValue(), "persons are not filtered by an entity filter");
        assertEquals(List.of("ISIN2"), JsonPath.read(entity, "$[0].rows[0].values"));
    }

    @Test
    void aSavedListKeepsItsFiltersIntoTheDataVersionAndTheFile() throws Exception {
        String saved = asMaker(post("/api/format-templates").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Paris only\",\"fields\":" + fields("[\"Paris\"]", "[]", "[]", "[]") + "}"));
        assertEquals(List.of("Paris"), JsonPath.read(saved, "$.fields[?(@.sourceColumn=='city')].filterValues[*]"));
        Number id = JsonPath.read(saved, "$.id");
        Number family = JsonPath.read(saved, "$.familyId");
        mockMvc.perform(post("/api/format-templates/" + id + "/approve").with(user("approver").roles("APPROVER"))).andExpect(status().isOk());

        // The list shows the same filters again.
        List<String> shown = JsonPath.read(asMaker(get("/api/format-templates")), "$[?(@.id==" + id + ")].fields[?(@.sourceColumn=='city')].filterValues[*]");
        assertEquals(List.of("Paris"), shown);

        // The live XML and a stored data version both contain only what the filters keep.
        String xml = download(get("/api/format-templates/" + id + "/xml"));
        assertTrue(xml.contains("<city>Paris</city>") && !xml.contains("London") && xml.contains("<tg_persons count=\"1\">"), xml);
        String taken = asMaker(post("/api/data-versions?familyId=" + family));
        assertEquals(1, ((Number) JsonPath.read(taken, "$.version.groups.tg_person")).intValue());
        assertEquals(1, ((Number) JsonPath.read(taken, "$.version.groups.tg_entity")).intValue());
        Number version = JsonPath.read(taken, "$.version.id");
        String stored = download(get("/api/data-versions/" + version + "/xml"));
        assertTrue(stored.contains("<city>Paris</city>") && !stored.contains("London") && !stored.contains("Maria"), stored);
        assertFalse(stored.contains("tg_person person_id=\"2\""), "the person without a matching address is not in the version");
    }

    private String asMaker(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user("maker").roles("MAKER"))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String download(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult started = mockMvc.perform(request.with(user("maker").roles("MAKER"))).andExpect(status().isOk()).andReturn();
        return mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
