package com.bnpp.export.dto;

import java.util.List;

/**
 * One row filter on a template column (a gs_ table and column that is on the template).
 *
 * @param op     in, not_in, contains, eq, ne, gt, gte, lt, lte, between, empty or not_empty
 * @param values the values the operator needs: the chosen values for in / not_in, one value for
 *               contains and the comparisons, two (from, to) for between; a date range may leave one empty
 * @param blank  in: also keep entries whose cell is empty; not_in: also leave those out
 */
public record FormatFilterRequest(
        String sourceTable,
        String sourceColumn,
        String op,
        List<String> values,
        Boolean blank
) {
}
