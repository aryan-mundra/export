package com.bnpp.export.config;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * The database chosen with the sidebar's Database switch, kept in {@code data/database.properties} (git-ignored)
 * so the module starts on it next time too: H2 (the in-memory demo database) or PostgreSQL with its connection.
 *
 * <p>An explicit profile (for example {@code --spring.profiles.active=postgres}, or {@code SPRING_PROFILES_ACTIVE})
 * always wins over this file; the switch is then turned off.
 */
public final class DatabaseSettings {

    public static final String H2 = "h2";
    public static final String POSTGRES = "postgres";

    private final String mode;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String lastError;

    public DatabaseSettings(String mode, String host, int port, String database, String username, String password, String lastError) {
        this.mode = POSTGRES.equals(mode) ? POSTGRES : H2;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.lastError = lastError;
    }

    /** Where the choice is kept: {@code data/database.properties}, or the {@code export.database.settings} system property. */
    public static Path file() {
        return Path.of(System.getProperty("export.database.settings", "data/database.properties"));
    }

    /** The saved choice, or H2 with no PostgreSQL connection when nothing was saved. */
    public static DatabaseSettings load() {
        Path file = file();
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(in);
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not read " + file, ex);
            }
        }
        int port;
        try {
            port = Integer.parseInt(values.getProperty("postgres.port", "5432"));
        } catch (NumberFormatException ex) {
            port = 5432;
        }
        return new DatabaseSettings(values.getProperty("mode", H2), values.getProperty("postgres.host", "localhost"), port,
                values.getProperty("postgres.database", ""), values.getProperty("postgres.username", ""),
                values.getProperty("postgres.password", ""), values.getProperty("last-error"));
    }

    public void save() {
        Properties values = new Properties();
        values.setProperty("mode", mode);
        values.setProperty("postgres.host", nullToEmpty(host));
        values.setProperty("postgres.port", String.valueOf(port));
        values.setProperty("postgres.database", nullToEmpty(database));
        values.setProperty("postgres.username", nullToEmpty(username));
        values.setProperty("postgres.password", nullToEmpty(password));
        if (lastError != null) {
            values.setProperty("last-error", lastError);
        }
        Path file = file();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                values.store(out, "Database chosen with the Database switch. Local to this machine: never commit it.");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not save " + file, ex);
        }
    }

    /**
     * The start-up arguments for this choice. Nothing is added when a profile was chosen explicitly, or for H2
     * (the default in application.yml).
     */
    public String[] arguments(String[] args) {
        if (profileChosenExplicitly(args) || !POSTGRES.equals(mode) || !hasConnection()) {
            return args;
        }
        List<String> all = new ArrayList<>(Arrays.asList(args));
        all.add("--spring.profiles.active=" + POSTGRES);
        all.add("--spring.datasource.url=" + jdbcUrl());
        all.add("--spring.datasource.username=" + username);
        all.add("--spring.datasource.password=" + password);
        return all.toArray(String[]::new);
    }

    /** A profile given on the command line, as a system property or in SPRING_PROFILES_ACTIVE. */
    public static boolean profileChosenExplicitly(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.startsWith("--spring.profiles.active"))
                || System.getProperty("spring.profiles.active") != null
                || System.getenv("SPRING_PROFILES_ACTIVE") != null;
    }

    public boolean hasConnection() {
        return host != null && !host.isBlank() && database != null && !database.isBlank() && username != null && !username.isBlank();
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    public DatabaseSettings withMode(String newMode, String error) {
        return new DatabaseSettings(newMode, host, port, database, username, password, error);
    }

    public String mode() {
        return mode;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /** Why the last switch to PostgreSQL did not start, or null. */
    public String lastError() {
        return lastError;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
