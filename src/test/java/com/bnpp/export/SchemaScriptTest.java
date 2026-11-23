package com.bnpp.export;

import com.bnpp.export.entity.AppUser;
import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.ExportAuditLog;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.ExportedFile;
import com.bnpp.export.entity.ExportedFileRecord;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatFilter;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.entity.QuarantineRecord;
import com.bnpp.export.entity.SnapshotSeries;
import com.bnpp.export.entity.SubscriberProfile;
import jakarta.persistence.Table;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.boot.model.relational.internal.SqlStringGenerationContextImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write schema: every table the module writes starts with ver_export_, and
 * db/postgres/ver-export-schema.sql creates each of them with every column the code uses. The DDL
 * Hibernate itself would generate for PostgreSQL is written to target/ver-export-schema.generated.sql,
 * to compare with when an entity changes.
 */
class SchemaScriptTest {

    /** Entities the module writes. CertifiedVersion maps REGLISS's own ver_version, which the module only reads. */
    static final List<Class<?>> WRITTEN = List.of(
            AppUser.class, SubscriberProfile.class, FormatTemplate.class, FormatField.class, FormatFilter.class,
            ExportJob.class, ExportJobSubscriberStatus.class, ExportedFile.class, ExportedFileRecord.class,
            DeliveryRecord.class, QuarantineRecord.class, ExportAuditLog.class,
            DataSnapshot.class, SnapshotSeries.class);

    private static final Pattern CREATE_TABLE = Pattern.compile("^create table (\\w+) \\((.*)\\)$", Pattern.MULTILINE);

    @Test
    void everyWrittenTableStartsWithVerAndTheScriptCreatesThemAll() throws Exception {
        for (Class<?> entity : WRITTEN) {
            String table = entity.getAnnotation(Table.class).name();
            assertTrue(table.startsWith("ver_export_"), entity.getSimpleName() + " writes to " + table + ", which does not start with ver_export_");
        }
        assertEquals("ver_version", CertifiedVersion.class.getAnnotation(Table.class).name());

        String ddl = generatedDdl();
        String script = Files.readString(Path.of("db", "postgres", "ver-export-schema.sql")).toLowerCase(Locale.ROOT);
        Matcher tables = CREATE_TABLE.matcher(ddl.toLowerCase(Locale.ROOT));
        int found = 0;
        while (tables.find()) {
            found++;
            String table = tables.group(1);
            int start = script.indexOf("create table if not exists " + table + " (");
            assertTrue(start >= 0, "ver-export-schema.sql does not create " + table);
            String body = script.substring(start, script.indexOf(");", start));
            for (String part : topLevel(tables.group(2))) {
                String column = part.trim().split("\\s+")[0];
                if (column.isEmpty() || column.equals("primary") || column.equals("unique") || column.equals("check")) {
                    continue;
                }
                assertTrue(Pattern.compile("\\n\\s*" + Pattern.quote(column) + "\\s").matcher(body).find(),
                        "ver-export-schema.sql: " + table + " has no column " + column);
            }
        }
        assertEquals(WRITTEN.size(), found, "tables in the generated DDL");
    }

    /** The PostgreSQL DDL Hibernate derives from the entities, also written to target/ for reference. */
    static String generatedDdl() throws Exception {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName())
                .applySetting("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName())
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            WRITTEN.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();
            JdbcEnvironment jdbc = registry.getService(JdbcEnvironment.class);
            SqlStringGenerationContext context = SqlStringGenerationContextImpl.forTests(jdbc);
            Dialect dialect = jdbc.getDialect();
            List<String> statements = new ArrayList<>();
            for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
                for (org.hibernate.mapping.Table table : namespace.getTables()) {
                    statements.addAll(List.of(dialect.getTableExporter().getSqlCreateStrings(table, metadata, context)));
                    for (org.hibernate.mapping.UniqueKey key : table.getUniqueKeys().values()) {
                        statements.addAll(List.of(dialect.getUniqueKeyExporter().getSqlCreateStrings(key, metadata, context)));
                    }
                    for (org.hibernate.mapping.Index index : table.getIndexes().values()) {
                        statements.addAll(List.of(dialect.getIndexExporter().getSqlCreateStrings(index, metadata, context)));
                    }
                }
            }
            String ddl = String.join("\n", statements) + "\n";
            Path generated = Path.of("target", "ver-export-schema.generated.sql");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, ddl);
            return ddl;
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** Splits a column list at commas that are not inside parentheses, e.g. numeric(38,2). */
    private static List<String> topLevel(String columns) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < columns.length(); i++) {
            char c = columns.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(columns.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(columns.substring(start));
        return parts;
    }
}
