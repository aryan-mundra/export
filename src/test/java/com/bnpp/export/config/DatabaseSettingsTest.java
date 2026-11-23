package com.bnpp.export.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The database chosen with the Database switch: kept in a local file, and turned into start-up arguments. */
class DatabaseSettingsTest {

    @TempDir
    Path folder;

    @BeforeEach
    void useTempFile() {
        System.setProperty("export.database.settings", folder.resolve("database.properties").toString());
    }

    @AfterEach
    void restore() {
        System.clearProperty("export.database.settings");
    }

    @Test
    void nothingSavedMeansH2WithNoExtraArguments() {
        DatabaseSettings settings = DatabaseSettings.load();
        assertEquals("h2", settings.mode());
        assertFalse(settings.hasConnection());
        assertArrayEquals(new String[] {"--x=1"}, settings.arguments(new String[] {"--x=1"}));
    }

    @Test
    void postgresIsSavedAndStartsWithItsConnection() throws Exception {
        new DatabaseSettings("postgres", "localhost", 5433, "shinedata", "regliss", "s3cret;=x", null).save();
        assertTrue(Files.readString(DatabaseSettings.file()).contains("mode=postgres"));

        DatabaseSettings loaded = DatabaseSettings.load();
        assertEquals("postgres", loaded.mode());
        assertEquals("jdbc:postgresql://localhost:5433/shinedata", loaded.jdbcUrl());
        assertEquals("s3cret;=x", loaded.password());
        assertEquals(List.of(
                        "--spring.profiles.active=postgres",
                        "--spring.datasource.url=jdbc:postgresql://localhost:5433/shinedata",
                        "--spring.datasource.username=regliss",
                        "--spring.datasource.password=s3cret;=x"),
                List.of(loaded.arguments(new String[0])));
    }

    @Test
    void anExplicitProfileWinsOverTheSavedChoice() {
        new DatabaseSettings("postgres", "localhost", 5432, "shinedata", "postgres", "pw", null).save();
        String[] args = {"--spring.profiles.active=mock"};
        assertArrayEquals(args, DatabaseSettings.load().arguments(args));
        assertTrue(DatabaseSettings.profileChosenExplicitly(args));
    }

    @Test
    void aFailedSwitchGoesBackToH2AndKeepsTheReasonAndConnection() {
        new DatabaseSettings("postgres", "db.local", 5432, "shinedata", "postgres", "pw", null)
                .withMode("h2", "PostgreSQL did not start: boom").save();
        DatabaseSettings loaded = DatabaseSettings.load();
        assertEquals("h2", loaded.mode());
        assertEquals("PostgreSQL did not start: boom", loaded.lastError());
        assertEquals("db.local", loaded.host());
        assertEquals(0, loaded.arguments(new String[0]).length);
        assertNull(loaded.withMode("h2", null).lastError());
    }
}
