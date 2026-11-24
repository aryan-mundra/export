package com.bnpp.export.dto;

import java.util.List;

/** What changed from one data version to another, with the changed records a page at a time. */
public record DataVersionComparisonResponse(Long newerId, String newerLabel, Long olderId, String olderLabel, boolean sameLayout,
                                            Counts counts, int offset, List<Change> changes, boolean more) {

    /** Records matched on record id: only in the newer version, only in the older one, changed, or the same. */
    public record Counts(long inserted, long deleted, long changed, long unchanged) {
    }

    /**
     * One value that differs between the two versions. An empty value (none, or blank) counts as no value.
     *
     * @param type         ADDED (no value before, one now), REMOVED (a value before, none now) or UPDATED (another value)
     * @param columnStatus null for a column in both versions; ADDED for a column only in the newer version, REMOVED for
     *                     a column only in the older one (the list's columns changed)
     */
    public record CellChange(String column, String before, String after, String type, String columnStatus) {

        public static final String ADDED = "ADDED";
        public static final String REMOVED = "REMOVED";
        public static final String UPDATED = "UPDATED";

        /** The change between two values, or null when they are the same (both empty counts as the same). */
        public static String typeOf(String before, String after) {
            boolean had = before != null && !before.isEmpty();
            boolean has = after != null && !after.isEmpty();
            if (!had && !has) {
                return null;
            }
            if (!had) {
                return ADDED;
            }
            if (!has) {
                return REMOVED;
            }
            return before.equals(after) ? null : UPDATED;
        }
    }

    /** kind: ADDED (only in the newer version), REMOVED (only in the older one) or CHANGED. */
    public record Change(long recordId, String bnpId, String kind, List<CellChange> cells) {
    }
}
