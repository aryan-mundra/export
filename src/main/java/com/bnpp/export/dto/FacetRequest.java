package com.bnpp.export.dto;

import java.util.List;

/** The values of one column of a list being edited, for its filter. {@code search} narrows them. */
public record FacetRequest(Long versionId,
                           List<FormatFieldRequest> fields,
                           List<FormatFilterRequest> filters,
                           Integer column,
                           String search) {
}
