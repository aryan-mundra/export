package com.bnpp.export.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo source data for the H2 database: VER_VERSION_MEMBER (the list entries of each certified
 * version) and the golden-source (gs_) tables. The module then reads them with exactly the SQL it
 * uses on PostgreSQL. Every gs_ row carries BNP_ID = "BNP" + entry id (versionId * 1,000,000 + index).
 * Some gs_ table names are quoted in mixed case on purpose: the gs_ prefix is matched without
 * regard to case.
 */
@Component
@ConditionalOnProperty(name = "export.mock.enabled", havingValue = "true")
@org.springframework.core.annotation.Order(0)
public class MockGoldenSourceSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(MockGoldenSourceSeeder.class);

    /** Mirrors the versions seeded by CertifiedVersionSeeder. */
    private static final long[] VERSIONS = {42L, 43L, 44L, 77L};

    private static final String[] NAMES = {
            "Amina Diallo", "Hugo Martin", "Priya Nair", "Elena Rossi", "Noah Keller",
            "Sofia Alvarez", "James Okonkwo", "Mei Chen", "Lars Andersen", "Fatima Haddad",
            "Owen Blake", "Chloe Dubois", "Ravi Kapoor", "Nora Berg", "Hassan Ali",
            "Emma Clarke", "Luca Bianchi", "Yuki Tanaka", "Ines Moreau", "Daniel Kruger"
    };
    private static final String[] ACTIONS = {"ADD", "MODIFY", "DELETE"};
    private static final String[] RISKS = {"LOW", "MEDIUM", "HIGH"};
    private static final String[] LINES = {"CIB", "Retail", "Compliance", "Financial Security"};
    private static final String[] REGIONS = {"EMEA", "APAC", "Europe", "Americas", "Global"};
    private static final String[] CITIES = {"Paris", "New York", "London", "Berlin", "Mumbai", "Singapore", "Dubai", "Zurich", "Tokyo", "Sao Paulo"};
    private static final String[] COUNTRIES = {"FR", "US", "GB", "DE", "IN", "SG", "AE", "CH", "JP", "BR"};
    private static final String[] STREETS = {"Rue de Rivoli", "Fifth Avenue", "Baker Street", "Unter den Linden", "Marine Drive"};
    private static final String[] PARTY_TYPES = {"INDIVIDUAL", "ENTITY", "VESSEL"};
    private static final String[] PROGRAMS = {"EU-CFSP", "OFAC-SDN", "UN-1267", "UK-HMT"};
    private static final String[] ID_TYPES = {"PASSPORT", "NATIONAL_ID", "TAX_ID"};

    private final JdbcTemplate jdbcTemplate;
    private final int entriesPerVersion;
    private final int recordsPerVersion;

    public MockGoldenSourceSeeder(JdbcTemplate jdbcTemplate, MockProperties mockProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.entriesPerVersion = Math.max(0, mockProperties.getRecordsPerSubscriber());
        // Every list entry has gs_ rows.
        this.recordsPerVersion = Math.max(400, entriesPerVersion);
    }

    /** The list entries of every demo version: all on every list, each with an action and a recent change date. */
    private void seedListEntries() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS VER_VERSION_MEMBER (
                    VERSION_ID BIGINT NOT NULL,
                    RECORD_ID BIGINT NOT NULL,
                    BNP_ID VARCHAR(20),
                    NAME VARCHAR(200),
                    COUNTRY VARCHAR(2),
                    RECORD_DATE DATE,
                    ACTION VARCHAR(10),
                    RISK_RATING VARCHAR(10),
                    BUSINESS_LINE VARCHAR(50),
                    REGION VARCHAR(50),
                    CHANGED_ON DATE,
                    ON_SANCTIONS SMALLINT DEFAULT 0 NOT NULL,
                    ON_WORLDCHECK SMALLINT DEFAULT 0 NOT NULL,
                    ON_PFA SMALLINT DEFAULT 0 NOT NULL,
                    PRIMARY KEY (VERSION_ID, RECORD_ID))""");
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM VER_VERSION_MEMBER", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        for (long version : VERSIONS) {
            List<Object[]> rows = new ArrayList<>();
            for (int index = 1; index <= entriesPerVersion; index++) {
                long id = version * 1_000_000L + index;
                LocalDate recordDate = LocalDate.of(1950 + (index % 50), (index % 12) + 1, (index % 27) + 1);
                rows.add(new Object[] {
                        version, id, "BNP" + id, NAMES[index % NAMES.length], COUNTRIES[index % COUNTRIES.length],
                        java.sql.Date.valueOf(recordDate), ACTIONS[index % ACTIONS.length], RISKS[index % 3],
                        LINES[index % 4], REGIONS[index % 5], java.sql.Date.valueOf(LocalDate.now().minusDays(index % 90)),
                        1, 1, 1});
            }
            jdbcTemplate.batchUpdate("INSERT INTO VER_VERSION_MEMBER VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        }
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS IX_VER_VERSION_MEMBER_BNP ON VER_VERSION_MEMBER (VERSION_ID, BNP_ID)");
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        seedListEntries();
        // Every gs_ table carries BNP_ID, the key shared with the list entries ("BNP" + entry id here).
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS GS_PARTY (
                    BNP_ID VARCHAR(20) PRIMARY KEY,
                    full_name VARCHAR(200),
                    party_type VARCHAR(30),
                    nationality VARCHAR(2),
                    birth_place VARCHAR(100),
                    risk_score INT)""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS "gs_address" (
                    "bnp_id" VARCHAR(20),
                    "street" VARCHAR(200),
                    "city" VARCHAR(100),
                    "postal_code" VARCHAR(20),
                    "country_code" VARCHAR(2))""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS "Gs_Identifier" (
                    "Bnp_Id" VARCHAR(20),
                    "id_type" VARCHAR(30),
                    "id_value" VARCHAR(60),
                    "issued_by" VARCHAR(2))""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS GS_SANCTION_PROGRAM (
                    BNP_ID VARCHAR(20),
                    program_code VARCHAR(30),
                    listed_on DATE)""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS GS_COUNTRY_RISK (
                    BNP_ID VARCHAR(20),
                    COUNTRY_CODE VARCHAR(2),
                    RISK_LEVEL VARCHAR(10),
                    SANCTIONED_REGIME VARCHAR(3),
                    LAST_REVIEW DATE)""");
        // No BNP_ID column: shown in the picker but cannot be used.
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS GS_LOAD_LOG (
                    load_ref VARCHAR(40),
                    loaded_at TIMESTAMP,
                    row_count INT)""");
        // Not a gs_ table, so it never appears in the picker.
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS REF_COUNTRY (
                    code VARCHAR(2),
                    name VARCHAR(100))""");

        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM GS_PARTY", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }

        String[] risks = {"LOW", "MEDIUM", "HIGH"};
        List<Object[]> parties = new ArrayList<>();
        List<Object[]> addresses = new ArrayList<>();
        List<Object[]> identifiers = new ArrayList<>();
        List<Object[]> programs = new ArrayList<>();
        List<Object[]> countryRisks = new ArrayList<>();
        for (long version : VERSIONS) {
            for (int index = 1; index <= recordsPerVersion; index++) {
                long id = version * 1_000_000L + index;
                String bnpId = "BNP" + id;
                int country = index % COUNTRIES.length;
                parties.add(new Object[] {
                        bnpId, "Party " + id, PARTY_TYPES[index % PARTY_TYPES.length],
                        COUNTRIES[country], CITIES[(index + 3) % CITIES.length], (index * 37) % 100});
                addresses.add(new Object[] {
                        bnpId, (index % 200 + 1) + " " + STREETS[index % STREETS.length],
                        CITIES[index % CITIES.length], String.format("%05d", (index * 131) % 100_000), COUNTRIES[country]});
                identifiers.add(new Object[] {
                        bnpId, ID_TYPES[index % ID_TYPES.length], "ID" + id, COUNTRIES[country]});
                if (index % 4 == 0) {
                    identifiers.add(new Object[] {
                            bnpId, ID_TYPES[(index + 1) % ID_TYPES.length], "AL" + id, COUNTRIES[(index + 1) % COUNTRIES.length]});
                }
                programs.add(new Object[] {
                        bnpId, PROGRAMS[index % PROGRAMS.length],
                        java.sql.Date.valueOf(java.time.LocalDate.of(2015 + index % 10, index % 12 + 1, index % 27 + 1))});
                countryRisks.add(new Object[] {
                        bnpId, COUNTRIES[country], risks[country % 3], country % 4 == 0 ? "YES" : "NO",
                        java.sql.Date.valueOf(java.time.LocalDate.of(2026, country % 12 + 1, 15))});
            }
        }
        jdbcTemplate.batchUpdate("INSERT INTO GS_PARTY VALUES (?, ?, ?, ?, ?, ?)", parties);
        jdbcTemplate.batchUpdate("INSERT INTO \"gs_address\" VALUES (?, ?, ?, ?, ?)", addresses);
        jdbcTemplate.batchUpdate("INSERT INTO \"Gs_Identifier\" VALUES (?, ?, ?, ?)", identifiers);
        jdbcTemplate.batchUpdate("INSERT INTO GS_SANCTION_PROGRAM VALUES (?, ?, ?)", programs);
        jdbcTemplate.batchUpdate("INSERT INTO GS_COUNTRY_RISK VALUES (?, ?, ?, ?, ?)", countryRisks);
        jdbcTemplate.update("INSERT INTO GS_LOAD_LOG VALUES ('LOAD-0001', CURRENT_TIMESTAMP, ?)", parties.size());
        jdbcTemplate.update("INSERT INTO REF_COUNTRY VALUES ('FR', 'France'), ('GB', 'United Kingdom')");
        log.info("Seeded mock golden-source tables with {} parties", parties.size());
    }
}
