package com.bnpp.export.model;

/** How a record differs between two data versions. */
public enum RecordChange {
    /** Only in the newer version. */
    ADDED,
    /** Only in the older version. */
    REMOVED,
    /** In both, with different content. */
    CHANGED,
    /** In both, identical. */
    UNCHANGED;

    /** Filters on record lists: one kind, every change (added, removed or changed), or every record. */
    public enum Filter { CHANGES, ADDED, REMOVED, CHANGED, UNCHANGED, ALL }
}
