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

/**
 * The PostgreSQL connection comes from config/database.properties; the Database switch's choice is kept in
 * data/database.properties; together they become the start-up arguments.
 */
class DatabaseSettingsTest {

    @TempDir
    Path folder;

    private Path connection;

    @BeforeEach
    void useTempFiles() {
        connection = folder.resolve("config-database.properties");
        System.setProperty("export.database.config", connection.toString());
        System.setProperty("export.database.settings", folder.resolve("data-database.properties").toString());
    }

    @AfterEach
    void restore() {
        System.clearProperty("export.database.config");
        System.clearProperty("export.database.settings");
    }

    private void writeConnection(String text) throws Exception {
        Files.writeString(connection, text);
    }

    @Test
    void noFilesMeansH2WithNoConnectionAndNoExtraArguments() {
        DatabaseSettings settings = DatabaseSettings.load();
        assertEquals("h2", settings.mode());
        assertFalse(settings.hasConnection());
        assertArrayEquals(new String[] {"--x=1"}, settings.arguments(new String[] {"--x=1"}));
    }

    @Test
    void theConnectionComesFromTheConfigFileAndTheChoiceFromTheDataFile() throws Exception {
        writeConnection("host=localhost\nport=5433\ndatabase=reglissData\nusername=postgres\npassword=s3cret;=x\n");
        DatabaseSettings onH2 = DatabaseSettings.load();
        assertTrue(onH2.hasConnection());
        assertEquals("h2", onH2.mode());
        assertEquals(0, onH2.arguments(new String[0]).length, "H2 until PostgreSQL is chosen");

        onH2.withMode("postgres", null).save();
        String saved = Files.readString(DatabaseSettings.choiceFile());
        assertTrue(saved.contains("mode=postgres"));
        assertFalse(saved.contains("s3cret"), "the password is never copied out of the config file");

        DatabaseSettings onPostgres = DatabaseSettings.load();
        assertEquals("jdbc:postgresql://localhost:5433/reglissData", onPostgres.jdbcUrl());
        assertEquals(List.of(
                        "--spring.profiles.active=postgres",
                        "--spring.datasource.url=jdbc:postgresql://localhost:5433/reglissData",
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=s3cret;=x"),
                List.of(onPostgres.arguments(new String[0])));
    }

    @Test
    void postgresWithoutAFilledInConfigFileStartsOnH2() throws Exception {
        writeConnection("host=localhost\nport=5432\ndatabase=\nusername=postgres\n");
        DatabaseSettings settings = DatabaseSettings.load().withMode("postgres", null);
        assertFalse(settings.hasConnection());
        assertEquals(0, settings.arguments(new String[0]).length);
    }

    @Test
    void anExplicitProfileWinsOverTheSavedChoice() throws Exception {
        writeConnection("host=localhost\ndatabase=reglissData\nusername=postgres\npassword=pw\n");
        DatabaseSettings.load().withMode("postgres", null).save();
        String[] args = {"--spring.profiles.active=mock"};
        assertArrayEquals(args, DatabaseSettings.load().arguments(args));
        assertTrue(DatabaseSettings.profileChosenExplicitly(args));
    }

    @Test
    void aFailedSwitchGoesBackToH2AndKeepsTheReason() throws Exception {
        writeConnection("host=db.local\ndatabase=reglissData\nusername=postgres\npassword=pw\n");
        DatabaseSettings.load().withMode("h2", "PostgreSQL did not start: boom").save();
        DatabaseSettings loaded = DatabaseSettings.load();
        assertEquals("h2", loaded.mode());
        assertEquals("PostgreSQL did not start: boom", loaded.lastError());
        assertEquals("db.local", loaded.host());
        assertNull(loaded.withMode("h2", null).lastError());
    }
}
