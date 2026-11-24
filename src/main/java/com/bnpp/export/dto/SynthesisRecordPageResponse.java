package com.bnpp.export.dto;

import java.util.List;

/** A page of a group's records across two data versions, with how each one changed. */
public record SynthesisRecordPageResponse(long total, int offset, List<Row> records) {

    /** @param change ADDED, REMOVED, CHANGED or UNCHANGED; summary: what changed, in a few words */
    public record Row(String key, String change, String summary) {
    }
}
