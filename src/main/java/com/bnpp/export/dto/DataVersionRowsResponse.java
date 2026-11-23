package com.bnpp.export.dto;

import java.util.List;
import java.util.Map;

/** A page of a data version's rows, in file order. */
public record DataVersionRowsResponse(List<Map<String, String>> columns, long total, int offset, List<Row> rows) {

    /** @param cells the list's values, in column order */
    public record Row(long line, long recordId, List<String> cells) {
    }
}
