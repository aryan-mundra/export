package com.bnpp.export.dto;

import java.util.List;

/** A page of lists that have data versions. */
public record DataVersionSeriesPageResponse(long total, int offset, List<DataVersionSeriesResponse> series) {
}
