package com.bnpp.export.dto;

import java.util.List;

/** The data versions of one list and source list, newest first, a page at a time. */
public record DataVersionPageResponse(DataVersionSeriesResponse series, long total, int offset, List<DataVersionResponse> versions) {
}
