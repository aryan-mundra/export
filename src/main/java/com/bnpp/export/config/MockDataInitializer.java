package com.bnpp.export.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "export.demo-data", havingValue = "true")
public class MockDataInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(MockDataInitializer.class);

    private static final String[][] SEED = {
            {"SUN", "XML", "ALL", "FULL", "CIB", "EMEA"},
            {"SHINE", "XML", "SANCTIONS", "DELTA", "Retail", "APAC"},
            {"Vigilance", "XML", "WORLDCHECK", "FULL", "Compliance", "Europe"},
            {"Actimize", "CSV", "PFA", "FULL", "Financial Security", "Americas"},
            {"NetReveal", "XML", "SANCTIONS", "FULL", "Financial Security", "EMEA"},
            {"Compliance Team", "CSV", "ALL", "FULL", "Compliance", "Global"}
    };

    private final JdbcTemplate jdbcTemplate;

    public MockDataInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ver_export_subscriber", Integer.class);
        if (count != null && count > 0) {
            log.info("ver_export_subscriber already has {} row(s); skipping mock seed", count);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Timestamp timestamp = Timestamp.valueOf(now);
        for (String[] row : SEED) {
            String endpoint = "http://cft.local/inbox/" + row[0].toLowerCase().replace(' ', '-');
            jdbcTemplate.update("""
                            INSERT INTO ver_export_subscriber
                            (name, format_type, scope, export_mode, business_line, region, cft_endpoint,
                             endpoint_status, active, max_batch_size, export_timeout_minutes, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    row[0], row[1], row[2], row[3], row[4], row[5], endpoint, "APPROVED", true,
                    10_000, 60, timestamp, timestamp);
        }
        log.info("Inserted {} mock subscribers", SEED.length);
    }
}
