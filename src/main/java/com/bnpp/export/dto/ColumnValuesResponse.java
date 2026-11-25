package com.bnpp.export.dto;

import java.util.List;

/**
 * The distinct values a column holds, to offer as filter choices.
 *
 * @param truncated true when the column holds more values than are listed (search to narrow them down)
 */
public record ColumnValuesResponse(List<String> values, boolean truncated) {
}
