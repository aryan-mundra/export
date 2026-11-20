package com.bnpp.export.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface RecordSelector {

    /** Streams the records of a version in batches. Implementations must not load the whole version at once. */
    void streamRecords(Long versionId,
                       String scope,
                       String exportMode,
                       LocalDate fromDate,
                       LocalDate toDate,
                       Consumer<List<Map<String, Object>>> consumer);

    /**
     * Counts what a subscriber would receive without reading the records, when the selector can do it in
     * the database. Empty means the caller must count by streaming.
     */
    default Optional<Counts> count(CountRequest request) {
        return Optional.empty();
    }

    /**
     * @param businessLine   null or blank for no business line filter
     * @param region         null or blank for no region filter
     * @param requiredFields record fields that must not be blank (blank ones are quarantined)
     */
    record CountRequest(Long versionId,
                        String scope,
                        String exportMode,
                        LocalDate fromDate,
                        LocalDate toDate,
                        String businessLine,
                        String region,
                        List<String> requiredFields) {
    }

    /** Records that would go in the file, and records that would be quarantined. */
    record Counts(long valid, long rejected) {
    }
}
