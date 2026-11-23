package com.bnpp.export.service;

import com.bnpp.export.dto.DataVersionComparisonResponse;
import com.bnpp.export.dto.DataVersionDetailResponse;
import com.bnpp.export.dto.DataVersionPageResponse;
import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.DataVersionRowsResponse;
import com.bnpp.export.dto.DataVersionSeriesPageResponse;
import com.bnpp.export.dto.FileDownload;

import java.util.List;

/**
 * Data versions as the API shows them: list, inspect, compare and download. Everything reads the versions'
 * own tables; the source tables are never queried.
 */
public interface DataVersionService {

    /** The newest data versions (at most 200), optionally of one list. */
    List<DataVersionResponse> latest(Long familyId);

    /** Lists and source lists that have data versions: one catalog row each, searched and paged. */
    DataVersionSeriesPageResponse series(String query, Long familyId, String sort, int offset, int limit);

    /** The versions of one list and source list, newest first, a page at a time. */
    DataVersionPageResponse versions(Long familyId, String listCode, int offset, int limit);

    /** A version with its column layout, the row filters that built it, and the files built from it. */
    DataVersionDetailResponse detail(Long id);

    /** A page of a version's rows, in file order. */
    DataVersionRowsResponse rows(Long id, int offset, int limit);

    /** What changed from {@code against} to {@code id}, with the changed records a page at a time. */
    DataVersionComparisonResponse compare(Long id, Long against, int offset, int limit);

    /**
     * A version as a file in any supported format. With a subscriber, only the rows it receives (its scope,
     * business line and region) that pass the quality check.
     */
    FileDownload download(Long id, String format, Long subscriberId);
}
