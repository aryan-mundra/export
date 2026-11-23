package com.bnpp.export.dto;

import java.util.List;

/**
 * What a column holds among the entries that pass the other filters: its distinct values with the number
 * of entries (text), or its smallest and largest value (numbers and dates), and how many entries have it empty.
 *
 * @param more true when there are more values than shown; a search narrows them
 */
public record FacetResponse(String kind, List<Value> values, boolean more, long blank, String min, String max) {

    public record Value(String value, long count) {
    }
}
