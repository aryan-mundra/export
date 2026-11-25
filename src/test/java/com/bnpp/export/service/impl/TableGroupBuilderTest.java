package com.bnpp.export.service.impl;

import com.bnpp.export.model.TableGroup;
import com.bnpp.export.repository.SourceConstraintRepository.Constraint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Groups discovered from primary and foreign keys: roots, levels, and what is left out. */
class TableGroupBuilderTest {

    private final List<Constraint> rows = new ArrayList<>();

    private void pk(String table, String column) {
        rows.add(new Constraint("s", table, column, table + "_pk", "PRIMARY KEY", 1, null, null, null));
    }

    private void fk(String table, String column, String refTable, String refColumn) {
        fk(table, column, refTable, refColumn, table + "_" + column + "_fk", 1);
    }

    private void fk(String table, String column, String refTable, String refColumn, String name, int position) {
        rows.add(new Constraint("s", table, column, name, "FOREIGN KEY", position, "s", refTable, refColumn));
    }

    @Test
    void buildsOneGroupPerRootWithEveryLevelBelowIt() {
        pk("person", "person_id");
        pk("person_name", "name_id");
        fk("person_name", "profile_ref", "person", "person_id");
        pk("person_address", "address_no");
        fk("person_address", "owner_id", "person", "person_id");
        pk("address_country", "id");
        fk("address_country", "addr_fk", "person_address", "address_no");
        pk("entity", "entity_id");
        pk("entity_code", "id");
        fk("entity_code", "ent_ref", "entity", "entity_id");

        List<TableGroup> groups = TableGroupBuilder.build(rows);

        assertEquals(List.of("entity", "person"), groups.stream().map(TableGroup::name).toList(), "one group per root, by table name");
        TableGroup person = groups.get(1);
        assertEquals("persons", person.section());
        assertEquals("person_id", person.root().key());
        assertEquals("s", person.schema());
        assertEquals(List.of("person_address", "person_name", "address_country"), person.links().stream().map(TableGroup.Link::table).toList(),
                "level by level");
        TableGroup.Link country = person.link("address_country").orElseThrow();
        assertEquals("addr_fk", country.key());
        assertEquals("person_address.address_no", person.joinTarget("address_country"));
        assertEquals("address_countries", country.section());
        assertEquals("address_no", person.link("person_address").orElseThrow().id(), "a one-column primary key identifies the rows");
        assertEquals("ent_ref", groups.get(0).link("entity_code").orElseThrow().key());
    }

    @Test
    void leavesOutWhatItCannotFollow() {
        pk("p", "id");
        pk("a", "id");
        fk("a", "p_ref", "p", "id");
        // a foreign key of two columns, a table pointing at itself, a table without a primary key, a foreign key into another schema
        pk("composite", "id");
        fk("composite", "c1", "p", "id", "comp_fk", 1);
        fk("composite", "c2", "p", "id", "comp_fk", 2);
        rows.add(new Constraint("s", "tree", "parent", "tree_fk", "FOREIGN KEY", 1, "s", "tree", "id"));
        pk("tree", "id");
        rows.add(new Constraint("s", "other", "x", "other_fk", "FOREIGN KEY", 1, "elsewhere", "t", "id"));
        pk("two_keys", "k1");
        pk("two_keys", "k2");
        // the module's own tables are never a group
        pk("ver_export_job", "id");
        pk("ver_export_job_subscriber", "id");
        fk("ver_export_job_subscriber", "export_job_id", "ver_export_job", "id");

        List<TableGroup> groups = TableGroupBuilder.build(rows);

        assertEquals(List.of("p", "tree"), groups.stream().map(TableGroup::name).toList(),
                "tree is a root (its own foreign key is ignored); composite and other have a foreign key we cannot follow, two_keys has no one-column key");
        assertEquals(List.of("a"), groups.get(0).links().stream().map(TableGroup.Link::table).toList());
        assertTrue(groups.get(1).links().isEmpty());
    }

    @Test
    void aTableBelongsToTheFirstGroupThatReachesIt() {
        pk("a", "id");
        pk("b", "id");
        pk("shared", "id");
        fk("shared", "b_ref", "b", "id");
        fk("shared", "a_ref", "a", "id");

        List<TableGroup> groups = TableGroupBuilder.build(rows);

        assertEquals("a_ref", groups.get(0).link("shared").orElseThrow().key());
        assertTrue(groups.get(1).links().isEmpty(), "a table is in one group only");
    }

    @Test
    void aRelatedTableWithoutAOneColumnPrimaryKeyHasNoRowId() {
        pk("p", "id");
        fk("link", "p_ref", "p", "id");

        TableGroup.Link link = TableGroupBuilder.build(rows).get(0).link("link").orElseThrow();

        assertNull(link.id());
    }

    @Test
    void noKeysNoGroups() {
        assertTrue(TableGroupBuilder.build(List.of()).isEmpty());
    }
}
