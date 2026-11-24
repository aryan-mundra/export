package com.bnpp.export.util;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} that starts at any row ({@code offset}), not only at a multiple of the page size: the
 * screens page with {@code offset} and {@code limit}. The order comes from the query itself.
 */
public record OffsetPage(long offset, int limit) implements Pageable {

    /** Page size used when none is asked for, and the largest one allowed (the other paged endpoints use 100). */
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    /** A page with the offset made non-negative and the limit kept between 1 and {@link #MAX_LIMIT}. */
    public static OffsetPage of(int offset, int limit) {
        return new OffsetPage(Math.max(0, offset), Math.min(MAX_LIMIT, Math.max(1, limit)));
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetPage(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPage(Math.max(0, offset - limit), limit) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPage(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPage((long) pageNumber * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
