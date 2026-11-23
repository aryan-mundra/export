package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFilterRequest;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatFilter;

import java.util.List;
import java.util.Map;

/**
 * Row filters of a template: which list entries go in the file.
 *
 * A filter tests one gs_ column of the template the way its cell shows it. A list entry passes when
 * any of its gs_ rows matches ("is one of", "contains", comparisons, date ranges) or, for the
 * negative forms ("is not one of", "≠"), when none does. An entry with no value in the column has an
 * empty cell. All filters of a template must pass.
 *
 * Every check is written as SQL on the list entry table, so the data explorer, the review counts
 * and the export (one query per batch) all apply exactly the same rule.
 */
public interface RowFilterService {

    /** The list entries of each certified version. */
    String ENTRY_TABLE = "VER_VERSION_MEMBER";

    /**
     * Checks requested filters against the template's (already checked) columns and returns them as
     * unsaved rows, with table and column names as the database spells them.
     */
    List<FormatFilter> validate(List<FormatFilterRequest> requested, List<FormatField> templateFields);

    List<String> values(FormatFilter filter);

    /** The filter in words, e.g. {@code gs_address.city is Paris, London}. */
    String describe(FormatFilter filter);

    /** An SQL condition on a list entry row (with its alias), and the values to bind, in order. */
    record Condition(String sql, List<Object> args) {

        public static final Condition NONE = new Condition("", List.of());

        public boolean isEmpty() {
            return sql.isEmpty();
        }

        /** " AND (...)" to append to a WHERE clause, or nothing. */
        public String and() {
            return isEmpty() ? "" : " AND (" + sql + ")";
        }
    }

    /** All filters as one condition on the list entry row {@code alias}; each must pass. */
    Condition condition(List<FormatFilter> filters, String alias);

    /**
     * Keeps the records of a batch that pass the filters, in their order: one query per 500 records
     * on the list entry table, by record id.
     */
    List<Map<String, Object>> keep(Long versionId, List<Map<String, Object>> records, Condition condition);
}
