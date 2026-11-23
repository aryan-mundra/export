package com.bnpp.export.dto;

import java.util.List;

/**
 * A page of the rows a list (still being edited) would put in a file.
 *
 * @param sortColumn    index of the list column to sort on, or null for list order (the file's order)
 * @param sortDirection asc or desc
 * @param withTotal     false when only the next page is needed (the counts are already known)
 */
public record ExploreRequest(Long versionId,
                             List<FormatFieldRequest> fields,
                             List<FormatFilterRequest> filters,
                             Integer sortColumn,
                             String sortDirection,
                             Integer offset,
                             Integer limit,
                             Boolean withTotal) {
}
