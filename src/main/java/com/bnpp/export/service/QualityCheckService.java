package com.bnpp.export.service;

import com.bnpp.export.entity.QuarantineRecord;

import java.util.List;
import java.util.Map;

/** QualityCheckService. */
public interface QualityCheckService {

    List<String> LIST_COLUMNS = List.of("id", "name", "country", "record_date", "action");

    String validateRecord(Map<String, Object> record, String formatType);

    List<String> columnsForRun(List<String> templateColumns);

    String validateRecord(Map<String, Object> record, List<String> columns);

    void acceptOmissions(Long subscriberStatusId, String acceptedBy);

    void saveQuarantineRecords(List<QuarantineRecord> records);
}
