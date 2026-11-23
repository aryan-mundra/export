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
 * Which database the module runs on, and the PostgreSQL connection.
 *
 * <ul>
 *   <li>The connection is read from {@code config/database.properties}: host, port, database, username, password.</li>
 *   <li>The database chosen with the sidebar's Database switch (h2 or postgres) is kept in
 *       {@code data/database.properties}, so the module starts on it next time too.</li>
 * </ul>
 *
 * <p>An explicit profile (for example {@code --spring.profiles.active=postgres}, or {@code SPRING_PROFILES_ACTIVE})
 * always wins over these files; the switch is then turned off.
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

    /** The PostgreSQL connection: {@code config/database.properties}, or the {@code export.database.config} system property. */
    public static Path connectionFile() {
        return Path.of(System.getProperty("export.database.config", "config/database.properties"));
    }

    /** The chosen database: {@code data/database.properties}, or the {@code export.database.settings} system property. */
    public static Path choiceFile() {
        return Path.of(System.getProperty("export.database.settings", "data/database.properties"));
    }

    /** The chosen database (H2 when none was chosen) with the connection from the config file. */
    public static DatabaseSettings load() {
        Properties choice = read(choiceFile());
        Properties connection = read(connectionFile());
        int port;
        try {
            port = Integer.parseInt(connection.getProperty("port", "5432").trim());
        } catch (NumberFormatException ex) {
            port = 5432;
        }
        return new DatabaseSettings(choice.getProperty("mode", H2), trim(connection.getProperty("host", "localhost")), port,
                trim(connection.getProperty("database", "")), trim(connection.getProperty("username", "")),
                connection.getProperty("password", ""), choice.getProperty("last-error"));
    }

    /** Saves the choice (and the reason a switch failed); the connection file is never written. */
    public void save() {
        Properties values = new Properties();
        values.setProperty("mode", mode);
        if (lastError != null) {
            values.setProperty("last-error", lastError);
        }
        Path file = choiceFile();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                values.store(out, "Database chosen with the Database switch (h2 or postgres). Local to this machine.");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not save " + file, ex);
        }
    }

    private static Properties read(Path file) {
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(in);
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not read " + file, ex);
            }
        }
        return values;
    }

    /**
     * The start-up arguments for this choice. Nothing is added when a profile was chosen explicitly, for H2 (the
     * default in application.yml), or when the connection file is not filled in.
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

    /** Whether config/database.properties names a host, a database and a user. */
    public boolean hasConnection() {
        return !host.isEmpty() && !database.isEmpty() && !username.isEmpty();
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
