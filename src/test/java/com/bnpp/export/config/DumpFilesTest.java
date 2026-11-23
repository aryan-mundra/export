package com.bnpp.export.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DumpFilesTest {

    @TempDir
    Path dir;

    @Test
    void readsTheTargetFromTheJdbcUrl() {
        DumpFiles.Target target = DumpFiles.target("jdbc:postgresql://localhost:5432/shinedata?sslmode=disable");
        assertEquals("localhost", target.host());
        assertEquals(5432, target.port());
        assertEquals("shinedata", target.database());
        assertEquals(5432, DumpFiles.target("jdbc:postgresql://db.local/regliss").port());
        assertEquals("jdbc:postgresql://localhost:5432/other?sslmode=disable",
                DumpFiles.withDatabase("jdbc:postgresql://localhost:5432/shinedata?sslmode=disable", "other"));
        assertThrows(IllegalArgumentException.class, () -> DumpFiles.target("jdbc:h2:mem:x"));
        assertThrows(IllegalArgumentException.class, () -> DumpFiles.target("jdbc:postgresql://h/bad-name"));
    }

    @Test
    void findsDumpsAndTellsPlainSqlFromArchives() throws Exception {
        Path plain = dir.resolve("b-shine.sql");
        Files.writeString(plain, """
                --
                -- PostgreSQL database dump
                --
                CREATE DATABASE shinedata WITH TEMPLATE = template0 ENCODING = 'UTF8';
                ALTER DATABASE shinedata OWNER TO shine_owner;
                \\connect shinedata
                CREATE TABLE public.party (id integer);
                ALTER TABLE public.party OWNER TO shine_owner;
                ALTER TABLE public.other OWNER TO "Reporting";
                ALTER SCHEMA public OWNER TO postgres;
                COPY public.party (id) FROM stdin;
                1
                \\.
                """, StandardCharsets.UTF_8);
        Path archive = dir.resolve("a-archive.dump");
        Files.write(archive, "PGDMP\u0001\u000e".getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(dir.resolve("notes.txt"), "not a dump");

        assertEquals(List.of(archive, plain), DumpFiles.dumps(dir));
        assertTrue(DumpFiles.isArchive(archive));
        assertFalse(DumpFiles.isArchive(plain));

        DumpFiles.PlainInfo info = DumpFiles.scan(plain);
        assertEquals(Set.of("shine_owner", "Reporting"), info.owners());
        assertEquals("shinedata", info.createsDatabase());

        Path simple = dir.resolve("c.sql");
        Files.writeString(simple, "CREATE TABLE t (id int);\n");
        assertNull(DumpFiles.scan(simple).createsDatabase());
        assertTrue(DumpFiles.scan(simple).owners().isEmpty());

        assertEquals(64, DumpFiles.sha256(plain).length());
        assertNotEquals(DumpFiles.sha256(plain), DumpFiles.sha256(simple));
        assertTrue(DumpFiles.dumps(dir.resolve("missing")).isEmpty());
    }
}
