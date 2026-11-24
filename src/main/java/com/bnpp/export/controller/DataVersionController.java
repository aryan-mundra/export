package com.bnpp.export.controller;

import com.bnpp.export.dto.DataVersionChangePageResponse;
import com.bnpp.export.dto.DataVersionComparisonResponse;
import com.bnpp.export.dto.DataVersionComparisonSummaryResponse;
import com.bnpp.export.dto.DataVersionDetailResponse;
import com.bnpp.export.dto.DataVersionPageResponse;
import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.DataVersionRowsResponse;
import com.bnpp.export.dto.DataVersionSeriesPageResponse;
import com.bnpp.export.service.ComparisonService;
import com.bnpp.export.service.DataVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** Data versions: list, inspect, compare and download. Everything reads the versions' own tables. */
@RestController
@RequestMapping("/api/data-versions")
public class DataVersionController {

    private final DataVersionService dataVersions;
    private final ComparisonService comparisons;

    public DataVersionController(DataVersionService dataVersions, ComparisonService comparisons) {
        this.dataVersions = dataVersions;
        this.comparisons = comparisons;
    }

    /** The newest data versions (at most 200), optionally of one list. Screens use the paged endpoints below. */
    @GetMapping
    public List<DataVersionResponse> list(@RequestParam(required = false) Long familyId) {
        return dataVersions.latest(familyId);
    }

    /** Lists and source lists that have data versions: one catalog row each, searched and paged. */
    @GetMapping("/series")
    public DataVersionSeriesPageResponse series(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) Long familyId,
                                                @RequestParam(defaultValue = "recent") String sort,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "50") int limit) {
        return dataVersions.series(q, familyId, sort, offset, limit);
    }

    /** The versions of one list and source list, newest first, a page at a time. */
    @GetMapping("/series/{familyId}/{listCode}")
    public DataVersionPageResponse versions(@PathVariable Long familyId,
                                            @PathVariable String listCode,
                                            @RequestParam(defaultValue = "0") int offset,
                                            @RequestParam(defaultValue = "25") int limit) {
        return dataVersions.versions(familyId, listCode, offset, limit);
    }

    @GetMapping("/{id}")
    public DataVersionDetailResponse detail(@PathVariable Long id) {
        return dataVersions.detail(id);
    }

    @GetMapping("/{id}/rows")
    public DataVersionRowsResponse rows(@PathVariable Long id,
                                        @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "100") int limit) {
        return dataVersions.rows(id, offset, limit);
    }

    @GetMapping("/{id}/compare/{against}")
    public DataVersionComparisonResponse compare(@PathVariable Long id,
                                                 @PathVariable Long against,
                                                 @RequestParam(defaultValue = "0") int offset,
                                                 @RequestParam(defaultValue = "50") int limit) {
        return dataVersions.compare(id, against, offset, limit);
    }

    /** A version as a file in any supported format; with a subscriber, only the rows it receives. */
    @GetMapping("/{id}/file")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "CSV") String format,
                                                      @RequestParam(required = false) Long subscriberId) {
        return FileResponses.temporary(dataVersions.download(id, format, subscriberId));
    }

    /** Comparing version {@code against} (before) with {@code id} (after): the counts and the changes per column. */
    @GetMapping("/{id}/compare/{against}/summary")
    public DataVersionComparisonSummaryResponse compareSummary(@PathVariable Long id, @PathVariable Long against) {
        return comparisons.summary(id, against);
    }

    /** A page of the records that differ, filtered by type (ALL, ADDED, REMOVED, CHANGED), column and BNP ID. */
    @GetMapping("/{id}/compare/{against}/changes")
    public DataVersionChangePageResponse compareChanges(@PathVariable Long id,
                                                        @PathVariable Long against,
                                                        @RequestParam(defaultValue = "ALL") String kind,
                                                        @RequestParam(required = false) String column,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(defaultValue = "0") int offset,
                                                        @RequestParam(defaultValue = "50") int limit) {
        return comparisons.changes(id, against, kind, column, q, offset, limit);
    }

    /** Every difference matching the filters as CSV, one line per changed value. */
    @GetMapping("/{id}/compare/{against}/file")
    public ResponseEntity<StreamingResponseBody> compareFile(@PathVariable Long id,
                                                             @PathVariable Long against,
                                                             @RequestParam(defaultValue = "ALL") String kind,
                                                             @RequestParam(required = false) String column,
                                                             @RequestParam(required = false) String q) {
        return FileResponses.temporary(comparisons.changesFile(id, against, kind, column, q));
    }
}
