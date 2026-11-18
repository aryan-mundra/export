package com.bnpp.export.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "export.mock.enabled", havingValue = "false", matchIfMissing = true)
public class ContentSelector implements RecordSelector {

    @SuppressWarnings("unused")
    private final JdbcTemplate jdbcTemplate;

    public ContentSelector(@Qualifier("reglissReadOnlyJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void streamRecords(Long versionId,
                              String scope,
                              String exportMode,
                              LocalDate fromDate,
                              LocalDate toDate,
                              Consumer<List<Map<String, Object>>> consumer) {
        throw new IllegalStateException(
                "PostgreSQL export is blocked. The reader still filters date of birth, does not apply business line or region, and has no certified-version catalog.");
    }
}
