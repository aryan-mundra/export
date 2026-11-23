package com.bnpp.export.dto;

import java.util.List;

/**
 * A page of the rows a list would put in a file.
 *
 * @param total   list entries that pass the filters (null when not asked for)
 * @param entries list entries in the version, before the filters (null when not asked for)
 */
public record ExplorePageResponse(Long versionId, String listName, List<Column> columns, Long total, Long entries,
                                  int offset, List<Row> rows, boolean more) {

    /** @param kind text, number, date or other: how the column can be filtered and sorted */
    public record Column(String header, String table, String column, String type, String kind) {
    }

    public record Row(Long id, List<String> cells) {
    }
}
