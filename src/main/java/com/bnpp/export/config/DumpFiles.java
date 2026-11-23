package com.bnpp.export.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** What the importer needs to know about a PostgreSQL dump file and the JDBC URL it imports into. */
final class DumpFiles {

    /** host, port and database of a jdbc:postgresql URL. */
    record Target(String host, int port, String database) {
    }

    private static final Pattern URL = Pattern.compile("^jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?;]+).*$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern OWNER = Pattern.compile("OWNER TO \"?([A-Za-z_][A-Za-z0-9_$]*)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_DATABASE = Pattern.compile("^CREATE DATABASE \"?([A-Za-z_][A-Za-z0-9_]*)\"?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BUILT_IN_ROLES = Set.of("postgres", "public", "current_user", "session_user");

    private DumpFiles() {
    }

    static Target target(String jdbcUrl) {
        Matcher matcher = URL.matcher(jdbcUrl == null ? "" : jdbcUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a PostgreSQL JDBC URL: " + jdbcUrl);
        }
        String database = matcher.group(3);
        requireName(database, "database");
        return new Target(matcher.group(1), matcher.group(2) == null ? 5432 : Integer.parseInt(matcher.group(2)), database);
    }

    static String withDatabase(String jdbcUrl, String database) {
        requireName(database, "database");
        return jdbcUrl.replaceFirst("^(jdbc:postgresql://[^/]+/)[^?;]+", "$1" + database);
    }

    static void requireName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsupported " + what + " name: " + name);
        }
    }

    /** Dump files in the folder, oldest name first: .sql, .dump and .backup. */
    static java.util.List<Path> dumps(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return java.util.List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".sql") || name.endsWith(".dump") || name.endsWith(".backup");
                    })
                    .sorted()
                    .toList();
        }
    }

    /** True for pg_dump's archive format (custom, -Fc), which pg_restore reads; false for plain SQL, which psql reads. */
    static boolean isArchive(Path file) throws IOException {
        byte[] head = new byte[5];
        try (InputStream in = Files.newInputStream(file)) {
            return in.read(head) == 5 && new String(head, StandardCharsets.US_ASCII).equals("PGDMP");
        }
    }

    /** What a plain SQL dump expects: the roles that own its objects, and the database it creates itself (if any). */
    record PlainInfo(Set<String> owners, String createsDatabase) {
    }

    static PlainInfo scan(Path file) throws IOException {
        Set<String> owners = new LinkedHashSet<>();
        String creates = null;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                // Data lines of COPY blocks never start with these keywords, so a cheap prefix test is enough.
                if (line.startsWith("ALTER ") && line.contains("OWNER TO")) {
                    Matcher owner = OWNER.matcher(line);
                    if (owner.find() && !BUILT_IN_ROLES.contains(owner.group(1).toLowerCase(Locale.ROOT))) {
                        owners.add(owner.group(1));
                    }
                } else if (creates == null && line.regionMatches(true, 0, "CREATE DATABASE", 0, 15)) {
                    Matcher database = CREATE_DATABASE.matcher(line);
                    if (database.find()) {
                        creates = database.group(1);
                    }
                }
            }
        }
        return new PlainInfo(owners, creates);
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            try (InputStream in = Files.newInputStream(file)) {
                for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
