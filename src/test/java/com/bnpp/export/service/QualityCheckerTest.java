package com.bnpp.export.service;

import com.bnpp.export.model.QuarantineRecord;
import com.bnpp.export.repository.QuarantineRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QualityCheckerTest {

    private final QuarantineRepository repository = mock(QuarantineRepository.class);
    private final QualityChecker checker = new QualityChecker(repository);

    @Test
    void acceptsACompleteRecord() {
        assertNull(checker.validateRecord(validRecord(), "CSV"));
    }

    @Test
    void rejectsABlankName() {
        Map<String, Object> record = validRecord();
        record.put("name", " ");
        assertEquals("Name is blank", checker.validateRecord(record, "FIRCOSOFT"));
    }

    @Test
    void csvChecksOnlyTheColumnsOnTheFile() {
        Map<String, Object> record = validRecord();
        record.put("name", " ");
        record.put("id", 9L);
        record.put("action", "ADD");
        record.put("record_date", "2026-11-18");
        assertNull(checker.validateRecord(record, List.of("id", "action", "record_date")));
        assertEquals("Name is blank", checker.validateRecord(record, List.of("id", "name")));
    }

    @Test
    void aMissingFlagIsNotAHold() {
        Map<String, Object> record = validRecord();
        record.put("sanctions", null);
        assertNull(checker.validateRecord(record, "FIRCOSOFT"));
        assertNull(checker.validateRecord(record, "CSV"));
    }

    @Test
    void everyFormatUsesTheSameIdentityColumns() {
        Map<String, Object> record = validRecord();
        record.put("name", " ");
        assertEquals("Name is blank", checker.validateRecord(record, "CSV"));
        assertEquals("Name is blank", checker.validateRecord(record, "FIRCOSOFT"));
        assertEquals(List.of("id", "name", "country", "record_date", "action"),
                checker.columnsForRun(List.of("id", "action", "record_date")));
    }

    @Test
    void savesQuarantineRecords() {
        QuarantineRecord record = new QuarantineRecord();
        record.setQuarantineReason("Country is blank");
        checker.saveQuarantineRecords(List.of(record));
        verify(repository).saveAll(List.of(record));
    }

    private Map<String, Object> validRecord() {
        return new java.util.LinkedHashMap<>(Map.of(
                "id", 1L,
                "name", "Amina Diallo",
                "country", "FR",
                "dateOfBirth", "1984-04-12",
                "status", "ACTIVE",
                "sanctions", true,
                "worldcheck", false,
                "pfa", false,
                "action", "ADD",
                "record_date", "1984-04-12"
        ));
    }
}
