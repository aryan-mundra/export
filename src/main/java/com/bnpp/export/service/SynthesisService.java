package com.bnpp.export.service;

import com.bnpp.export.dto.SynthesisRecordPageResponse;
import com.bnpp.export.dto.SynthesisRecordResponse;
import com.bnpp.export.dto.SynthesisSummaryResponse;
import com.bnpp.export.model.RecordChange;

/** Synthesis: what changed between two data versions of a list, per group, table, column and record. */
public interface SynthesisService {

    SynthesisSummaryResponse summary(Long after, Long before);

    /** A page of a group's records, filtered by change and key text. */
    SynthesisRecordPageResponse records(Long after, Long before, String group, RecordChange.Filter filter, String query,
                                        int offset, int limit);

    /** One record in both versions, with every difference. */
    SynthesisRecordResponse record(Long after, Long before, String group, String key);
}
