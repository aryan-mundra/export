package com.bnpp.export.service;

import com.bnpp.export.model.QuarantineRecord;
import com.bnpp.export.repository.QuarantineRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QualityChecker {

    public static final List<String> LIST_COLUMNS = List.of("id", "name", "country", "record_date", "action");

    private final QuarantineRepository quarantineRepository;

    public QualityChecker(QuarantineRepository quarantineRepository) {
        this.quarantineRepository = quarantineRepository;
    }

    public String validateRecord(Map<String, Object> record, String formatType) {
        if (isBlank(formatType)) {
            return "Format type is blank";
        }
        return validateRecord(record, LIST_COLUMNS);
    }

    public List<String> columnsForRun(List<String> templateColumns) {
        java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>(LIST_COLUMNS);
        if (templateColumns != null) {
            columns.addAll(templateColumns);
        }
        return List.copyOf(columns);
    }

    public String validateRecord(Map<String, Object> record, List<String> columns) {
        if (record == null) {
            return "Record is missing";
        }
        if (columns == null || columns.isEmpty()) {
            return "Format type is blank";
        }
        for (String column : columns) {
            if (isBlank(value(record, column))) {
                return message(column);
            }
        }
        return null;
    }

    public void acceptOmissions(Long subscriberStatusId, String acceptedBy) {
        List<QuarantineRecord> rows = quarantineRepository
                .findBySubscriberStatusIdAndPatchAppliedFalse(subscriberStatusId);
        java.time.LocalDateTime acceptedAt = java.time.LocalDateTime.now();
        for (QuarantineRecord row : rows) {
            row.setPatchApplied(true);
            row.setAcceptedBy(acceptedBy);
            row.setAcceptedAt(acceptedAt);
        }
        saveQuarantineRecords(rows);
    }

    private Object value(Map<String, Object> record, String column) {
        Object value = record.get(column);
        if (!isBlank(value)) {
            return value;
        }
        if ("record_date".equals(column)) {
            return record.get("dateOfBirth");
        }
        if ("dateOfBirth".equals(column)) {
            return record.get("record_date");
        }
        return value;
    }

    private String message(String column) {
        return switch (column) {
            case "name" -> "Name is blank";
            case "country" -> "Country is blank";
            case "dateOfBirth" -> "Date of birth is blank";
            case "record_date" -> "Record date is blank";
            case "sanctions" -> "Sanctions flag is missing";
            case "worldcheck" -> "Worldcheck flag is missing";
            case "pfa" -> "Pfa flag is missing";
            default -> column + " is blank";
        };
    }

    public void saveQuarantineRecords(List<QuarantineRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        quarantineRepository.saveAll(records);
    }

    private boolean isBlank(Object value) {
        return value == null || StringUtils.isBlank(String.valueOf(value));
    }
}
