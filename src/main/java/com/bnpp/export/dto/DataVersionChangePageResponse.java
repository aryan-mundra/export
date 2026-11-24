package com.bnpp.export.dto;

import java.util.List;

/**
 * A page of the records that differ between two versions, after the filters (type, column, BNP ID search).
 * A changed record carries only the values that differ; an added or removed record carries its values.
 *
 * @param total records matching the filters, across all pages
 */
public record DataVersionChangePageResponse(long total, int offset, int limit, List<DataVersionComparisonResponse.Change> changes) {
}
