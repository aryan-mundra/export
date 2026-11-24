package com.bnpp.export.config;

import org.junit.jupiter.api.Test;
import com.bnpp.export.model.TableGroup;
import org.yaml.snakeyaml.Yaml;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading config/table-groups.yml: defaults, and a clear message for every mistake. */
class TableGroupsConfigTest {

    @Test
    void readsGroupsWithDefaults() {
        List<TableGroup> groups = parse("""
                groups:
                  - name: Person
                    root: {table: p, key: person_id}
                    tables:
                      - {table: p_address, key: owner_id, references: p.person_id}
                      - {table: p_country, key: addr_fk, references: p_address.address_no, element: Country}
                  - name: Entity
                    root: {table: e, key: entity_id}
                """);
        assertEquals(2, groups.size());
        TableGroup person = groups.get(0);
        assertEquals("Persons", person.section());
        assertEquals("p_address", person.links().get(0).element());
        assertEquals("p_addresses", person.links().get(0).section());
        assertEquals("Countries", person.links().get(1).section());
        assertEquals("p_address.address_no", person.joinTarget("p_country"));
        assertEquals("owner_id", person.joinColumn("p_address"));
        assertEquals("Entities", groups.get(1).section());
        assertEquals(List.of(), groups.get(1).links());
    }

    @Test
    void explainsMistakes() {
        assertMessage("groups:", "must start with \"groups:\"");
        assertMessage("""
                groups:
                  - name: Person
                    tables: []
                """, "group Person has no root");
        assertMessage("""
                groups:
                  - name: Person
                    root: {table: p, key: id}
                    tables:
                      - {table: q, key: pid, references: p_id}
                """, "references must be written table.column");
        assertMessage("""
                groups:
                  - name: Person
                    root: {table: p, key: id}
                    tables:
                      - {table: q, key: pid, references: other.id}
                """, "which is not a table of this group");
        assertMessage("""
                groups:
                  - name: Person
                    root: {table: p, key: id}
                    tables:
                      - {table: a, key: x, references: b.id}
                      - {table: b, key: y, references: a.id}
                """, "go round in a loop");
        assertMessage("""
                groups:
                  - name: Person
                    root: {table: p, key: id}
                  - name: Entity
                    root: {table: p, key: id}
                """, "a table belongs to one group");
        assertMessage("""
                groups:
                  - name: Legal person
                    root: {table: p, key: id}
                """, "cannot be an XML name");
    }

    private static void assertMessage(String yaml, String expected) {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse(yaml));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private static List<TableGroup> parse(String yaml) {
        return TableGroupsConfig.parse(new Yaml().load(yaml));
    }
}
