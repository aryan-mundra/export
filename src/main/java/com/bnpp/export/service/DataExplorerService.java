package com.bnpp.export.service;

import com.bnpp.export.dto.ExplorePageResponse;
import com.bnpp.export.dto.ExploreRequest;
import com.bnpp.export.dto.FacetRequest;
import com.bnpp.export.dto.FacetResponse;

/**
 * The data behind a template that is being built: one row per list entry of a certified version,
 * with the template's gs_ columns, exactly as the file would hold them, narrowed by the template's
 * row filters. Paged, sorted and counted in the database, so the size of the version does not matter.
 * Nothing is stored.
 */
public interface DataExplorerService {

    int MAX_PAGE = 500;

    int FACET_VALUES = 200;

    ExplorePageResponse explore(ExploreRequest request);

    FacetResponse facet(FacetRequest request);
}
