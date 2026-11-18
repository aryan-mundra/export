package com.bnpp.export.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface RecordSelector {

    void streamRecords(Long versionId,
                       String scope,
                       String exportMode,
                       LocalDate fromDate,
                       LocalDate toDate,
                       Consumer<List<Map<String, Object>>> consumer);
}
