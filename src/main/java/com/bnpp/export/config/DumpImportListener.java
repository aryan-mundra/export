package com.bnpp.export.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Imports PostgreSQL dumps dropped into a folder (db/dumps by default) when the application starts,
 * before anything connects to the database. It only loads data: it creates no database, table or role.
 *
 * <ul>
 *   <li>The database (from spring.datasource.url) and the ver_export_ tables (db/postgres/ver-export-schema.sql)
 *       are created by hand beforehand; if either is missing, start-up stops and says what to run.</li>
 *   <li>Plain SQL dumps run through psql (they use COPY ... FROM stdin and backslash commands that plain JDBC
 *       cannot run); pg_dump archives (-Fc, "PGDMP") through pg_restore --no-owner.</li>
 *   <li>Each imported file is recorded by content hash in ver_export_dump_import, so a restart does not import it
 *       again. A different dump is not loaded over data already imported: that is done by hand.</li>
 * </ul>
 *
 * Needs psql / pg_restore: from export.import.bin-dir, the PATH, or C:Program FilesPostgreSQL*in.
 * Enabled by export.import.enabled (on in the postgres profile).
 */
public class DumpImportListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DumpImportListener.class);
    private static final String LOG_TABLE = "ver_export_dump_import";

    @Override
    public int getOrder() {
        // After Spring Boot's own listeners (logging, config files).
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        if (!env.getProperty("export.import.enabled", Boolean.class, false)) {
            return;
        }
        String url = env.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:postgresql:")) {
            log.info("Dump import skipped: the datasource is not PostgreSQL ({})", url);
            return;
        }
        Path folder = Path.of(env.getProperty("export.import.directory", "db/dumps")).toAbsolutePath().normalize();
        try {
            List<Path> dumps = DumpFiles.dumps(folder);
            if (dumps.isEmpty()) {
                log.info("No database dump to import. To load one, put the .sql file in {} and restart.", folder);
                return;
            }
            new Run(env, url, folder).importAll(dumps);
        } catch (ImportFailed ex) {
            throw new IllegalStateException("Database dump import failed: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Database dump import failed: " + ex.getMessage(), ex);
        }
    }

    static final class ImportFailed extends RuntimeException {
        ImportFailed(String message) {
            super(message);
        }
    }

    /** One start-up's import: the target database, the tools, and the commands. */
    private static final class Run {

        private final DumpFiles.Target target;
        private final String user;
        private final String password;
        private final Path bin;
        private final Path logs;

        Run(ConfigurableEnvironment env, String url, Path folder) throws IOException {
            this.target = DumpFiles.target(url);
            this.user = env.getProperty("spring.datasource.username", "postgres");
            this.password = env.getProperty("spring.datasource.password", "");
            this.bin = findTools(env.getProperty("export.import.bin-dir", ""));
            this.logs = folder.resolve("import-logs");
            Files.createDirectories(logs);
        }

        void importAll(List<Path> dumps) throws IOException {
            for (Path dump : dumps) {
                importOne(dump);
            }
        }

        private void importOne(Path dump) throws IOException {
            String name = dump.getFileName().toString();
            String database = target.database();
            boolean archive = DumpFiles.isArchive(dump);
            DumpFiles.PlainInfo info = archive ? new DumpFiles.PlainInfo(java.util.Set.of(), null) : DumpFiles.scan(dump);
            if (info.createsDatabase() != null) {
                throw new ImportFailed(name + " creates its own database (\"" + info.createsDatabase() + "\"). The application only "
                        + "imports data into the database you created. Ask for a dump made without --create, or remove its "
                        + "CREATE DATABASE and \\connect lines.");
            }
            // Nothing is created from here: the database and the ver_export_ tables are made by hand beforehand.
            if (!databaseExists(database)) {
                throw new ImportFailed("Database \"" + database + "\" does not exist. Create it first (createdb -U postgres "
                        + database + ", or in pgAdmin), then run db/postgres/ver-export-schema.sql in it.");
            }
            if (!"1".equals(query(database, "SELECT 1 FROM pg_tables WHERE tablename = '" + LOG_TABLE + "'"))) {
                throw new ImportFailed("The ver_export_ tables are missing in \"" + database + "\". Run db/postgres/ver-export-schema.sql "
                        + "in it once (psql -U postgres -d " + database + " -f db/postgres/ver-export-schema.sql), then restart.");
            }
            String hash = DumpFiles.sha256(dump);
            if ("1".equals(query(database, "SELECT 1 FROM " + LOG_TABLE + " WHERE sha256 = '" + hash + "'"))) {
                log.info("{} is already imported into \"{}\"; nothing to do.", name, database);
                return;
            }
            String previous = query(database, "SELECT file_name FROM " + LOG_TABLE + " ORDER BY imported_at DESC LIMIT 1");
            if (previous != null) {
                log.warn("\"{}\" already holds the data of {}, so {} was NOT imported. To load the new dump, drop and recreate the "
                        + "database by hand, run db/postgres/ver-export-schema.sql again, and restart.", database, previous, name);
                return;
            }

            long started = System.currentTimeMillis();
            log.info("Importing {} ({} MB) into \"{}\". Large dumps can take several minutes.", name,
                    Files.size(dump) / (1024 * 1024), database);
            Path errors = logs.resolve(name + ".log");
            int code = archive
                    ? run(List.of(tool("pg_restore"), "--no-owner", "--no-privileges", "-d", database, dump.toString()), errors)
                    // Ownership lines (ALTER ... OWNER TO a role that does not exist here) fail harmlessly:
                    // the objects stay owned by the importing user. They are listed in the log below.
                    : run(List.of(tool("psql"), "-X", "-q", "-d", database, "-f", dump.toString()), errors);
            List<String> problems = errorLines(errors);
            if (code != 0 && problems.isEmpty()) {
                throw new ImportFailed(name + " could not be imported (exit code " + code + "). See " + errors);
            }
            psql(database, List.of("-c", "INSERT INTO " + LOG_TABLE + " (sha256, file_name, file_size, errors) VALUES ('"
                    + hash + "', '" + name.replace("'", "''") + "', " + Files.size(dump) + ", " + problems.size() + ")"), true);
            long seconds = (System.currentTimeMillis() - started) / 1000;
            long ownership = problems.stream().filter(line -> line.contains("role") && line.contains("does not exist")).count();
            if (problems.isEmpty()) {
                log.info("Imported {} into \"{}\" in {} s.", name, database, seconds);
            } else {
                log.warn("Imported {} into \"{}\" in {} s with {} error(s){}. The first ones:\n  {}\nFull log: {}", name, database,
                        seconds, problems.size(), ownership > 0 ? " (" + ownership + " only about owners that do not exist here)" : "",
                        String.join("\n  ", problems.subList(0, Math.min(10, problems.size()))), errors);
            }
        }

        private boolean databaseExists(String database) {
            return "1".equals(query("postgres", "SELECT 1 FROM pg_database WHERE datname = '" + database + "'"));
        }

        /** One value from a query, or null. */
        private String query(String database, String sql) {
            try {
                Path out = Files.createTempFile("regliss-psql", ".out");
                try {
                    int code = run(List.of(tool("psql"), "-X", "-tA", "-d", database, "-c", sql), out);
                    String text = Files.readString(out, StandardCharsets.UTF_8).trim();
                    if (code != 0) {
                        if (text.contains("does not exist")) {
                            return null;
                        }
                        throw new ImportFailed(firstLine(text));
                    }
                    return text.isEmpty() ? null : text.lines().findFirst().orElse(null);
                } finally {
                    Files.deleteIfExists(out);
                }
            } catch (IOException ex) {
                throw new ImportFailed(ex.getMessage());
            }
        }

        private void psql(String database, List<String> args, boolean mustSucceed) {
            try {
                Path out = Files.createTempFile("regliss-psql", ".out");
                try {
                    List<String> command = new ArrayList<>(List.of(tool("psql"), "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", database));
                    command.addAll(args);
                    int code = run(command, out);
                    if (code != 0 && mustSucceed) {
                        throw new ImportFailed(firstLine(Files.readString(out, StandardCharsets.UTF_8)));
                    }
                } finally {
                    Files.deleteIfExists(out);
                }
            } catch (IOException ex) {
                throw new ImportFailed(ex.getMessage());
            }
        }

        /** Runs a PostgreSQL tool with the connection in its environment; its output goes to {@code output}. */
        private int run(List<String> command, Path output) {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            Map<String, String> environment = builder.environment();
            environment.put("PGHOST", target.host());
            environment.put("PGPORT", String.valueOf(target.port()));
            environment.put("PGUSER", user);
            environment.put("PGPASSWORD", password);
            environment.put("PGCLIENTENCODING", "UTF8");
            try {
                Process process = builder.start();
                if (!process.waitFor(6, TimeUnit.HOURS)) {
                    process.destroyForcibly();
                    throw new ImportFailed(String.join(" ", command.subList(0, 1)) + " did not finish within 6 hours");
                }
                return process.exitValue();
            } catch (IOException ex) {
                throw new ImportFailed("Could not run " + command.get(0) + ": " + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ImportFailed("Interrupted while importing");
            }
        }

        private static List<String> errorLines(Path log) throws IOException {
            if (!Files.exists(log)) {
                return List.of();
            }
            try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
                return lines.filter(line -> line.contains("ERROR:") || line.contains("FATAL:")).toList();
            }
        }

        private static String firstLine(String text) {
            return text == null || text.isBlank() ? "unknown error" : text.strip().lines().findFirst().orElse(text);
        }

        private String tool(String name) {
            String file = isWindows() ? name + ".exe" : name;
            return bin == null ? name : bin.resolve(file).toString();
        }

        /**
         * Where psql and pg_restore are: export.import.bin-dir, else the PATH, else the newest
         * C:\Program Files\PostgreSQL\<version>\bin.
         */
        private static Path findTools(String configured) {
            String psql = isWindows() ? "psql.exe" : "psql";
            if (configured != null && !configured.isBlank()) {
                Path dir = Path.of(configured.trim());
                if (Files.isRegularFile(dir.resolve(psql))) {
                    return dir;
                }
                throw new ImportFailed("psql was not found in export.import.bin-dir (" + dir + ")");
            }
            for (String entry : System.getenv().getOrDefault("PATH", System.getenv().getOrDefault("Path", "")).split(File.pathSeparator)) {
                if (!entry.isBlank() && Files.isRegularFile(Path.of(entry.trim()).resolve(psql))) {
                    return Path.of(entry.trim());
                }
            }
            if (isWindows()) {
                Path root = Path.of("C:\\Program Files\\PostgreSQL");
                if (Files.isDirectory(root)) {
                    try (Stream<Path> versions = Files.list(root)) {
                        Path newest = versions.filter(version -> Files.isRegularFile(version.resolve("bin").resolve(psql)))
                                .max(Comparator.comparingInt(version -> versionNumber(version.getFileName().toString())))
                                .orElse(null);
                        if (newest != null) {
                            return newest.resolve("bin");
                        }
                    } catch (IOException ignored) {
                        // fall through to the error below
                    }
                }
            }
            throw new ImportFailed("psql was not found. Install PostgreSQL (with Command Line Tools), or set PG_BIN to its bin folder, "
                    + "e.g. C:\\Program Files\\PostgreSQL\\17\\bin");
        }

        private static int versionNumber(String text) {
            try {
                return Integer.parseInt(text.replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }
}
