package com.bnpp.export.dto;

import java.util.List;

/** What changed from one data version to another, with the changed records a page at a time. */
public record DataVersionComparisonResponse(Long newerId, String newerLabel, Long olderId, String olderLabel, boolean sameLayout,
                                            Counts counts, int offset, List<Change> changes, boolean more) {

    /** Records matched on record id: only in the newer version, only in the older one, changed, or the same. */
    public record Counts(long inserted, long deleted, long changed, long unchanged) {
    }

    /** One value that differs between the two versions. */
    public record CellChange(String column, String before, String after) {
    }

    /** kind: ADDED (only in the newer version), REMOVED (only in the older one) or CHANGED. */
    public record Change(long recordId, String bnpId, String kind, List<CellChange> cells) {
    }
}
