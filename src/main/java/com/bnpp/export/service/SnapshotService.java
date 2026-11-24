package com.bnpp.export.service;

import com.bnpp.export.dto.DataVersionComparisonResponse;
import com.bnpp.export.dto.DataVersionPageResponse;
import com.bnpp.export.dto.DataVersionRowsResponse;
import com.bnpp.export.dto.DataVersionSeriesPageResponse;
import com.bnpp.export.dto.DataVersionSeriesResponse;
import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.repository.VersionTableRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Data versions (snapshots) of what a template produces from a list.
 *
 * <p>Building: the list entries of the certified version are read once, passed through the template's
 * row filters and given their gs_ values, exactly as an export does. Each row is written in a fixed
 * form (every value, empty ones included) and hashed; the dataset fingerprint is the hash of the
 * column layout and all row hashes in record order. Rows are staged in a file, so nothing large is
 * held in memory and nothing is written to the database until we know the data is new.
 *
 * <p>Numbering: under a lock on the template and list, an existing version with the same template
 * version and fingerprint is reused; otherwise the next number is taken. A unique key on
 * (template, list, fingerprint) backs this up, so two exports at the same moment cannot create twin
 * versions or the same number twice.
 *
 * <p>Storage: one header row per version in ver_export_snapshot, and the version's rows in a table of its
 * own, {@code ver_<list>_v<major>_<minor>} (see {@link VersionTableRepository}), with the list's columns and the
 * filtered rows, in file order.
 *
 * <p>Reading: files are built from the version's table only, so a historical version never needs the
 * source query or joins again, and the same rows can be written in any format.
 */
public interface SnapshotService {

    /** The data version an export used, and whether the export created it or found the same data already stored. */
    record Resolution(DataSnapshot snapshot, boolean created) {
    }

    /**
     * A page of templates and lists that have data versions, from the catalog table (one row each),
     * searched by template name, list name or list code. No version is read.
     *
     * @param sort "recent" (last used first) or "name"
     */
    DataVersionSeriesPageResponse series(String query, Long familyId, String sort, int offset, int limit);

    DataVersionSeriesResponse seriesOf(Long familyId, String listCode);

    /** The versions of one template and list, newest first, a page at a time (headers only, no rows). */
    DataVersionPageResponse versions(Long familyId, String listCode, int offset, int limit);

    DataSnapshot get(Long id);

    /**
     * Reads what the template produces from the certified version now, and returns the matching data
     * version: an existing one when the data is identical, otherwise a new one.
     */
    Resolution resolve(Long certifiedVersionId, FormatTemplate template, Long jobId);

    List<Map<String, String>> columns(DataSnapshot snapshot);

    // ------------------------------------------------------------------ reading

    /** Which rows of a version to read: all of them, or what one subscriber receives. */
    record Selection(String scope, String exportMode, LocalDate fromDate, LocalDate toDate) {
        public static final Selection ALL = new Selection("ALL", "FULL", null, null);
    }

    /**
     * Streams the rows of a version from its own table, in line order, as export records: the same keys
     * the source reader and the gs_ enricher produce, so quality checks, profile filters and file writers
     * work unchanged. The source tables are not read.
     */
    void stream(DataSnapshot snapshot, Selection selection, List<FormatField> fields, Consumer<Map<String, Object>> consumer);

    /** A page of a version's rows, in file order. */
    List<DataVersionRowsResponse.Row> rows(DataSnapshot snapshot, int offset, int limit);

    /** How {@code newer} differs from {@code older}, record by record (matched on record id). */
    DataVersionComparisonResponse.Counts counts(Long newer, Long older);

    DataVersionComparisonResponse compare(Long newerId, Long olderId, int offset, int limit);

    /**
     * The list entry fields compared besides the list's own columns: field (its version table column without the
     * leading "_") and the name shown for it.
     */
    String[][] ENTRY_FIELDS = {
            {"name", "Entry name"}, {"country", "Entry country"}, {"record_date", "Record date"}, {"action", "Action"},
            {"risk_rating", "Risk rating"}, {"business_line", "Business line"}, {"region", "Region"},
            {"changed_on", "Changed on"}, {"on_sanctions", "On Sanctions"}, {"on_worldcheck", "On World-Check"}, {"on_pfa", "On PFA"}
    };

    /** Suffix of the names of list entry fields in comparisons, e.g. "Entry name (list entry)". */
    String ENTRY_SUFFIX = " (list entry)";

    /**
     * What changed for each of these records between two versions of one list, in the given order: ADDED (only in
     * {@code newer}), REMOVED (only in {@code older}) or CHANGED, with the values that differ.
     */
    List<DataVersionComparisonResponse.Change> describeChanges(DataSnapshot newer, DataSnapshot older, List<Long> recordIds);

    /**
     * Writes a version as a file in any format, from the stored rows. With a subscriber, only the rows
     * it receives (scope, business line and region) and that pass the quality check, as an export does.
     *
     * @param keep decides per record whether it goes in the file (null keeps all)
     */
    StorageService.PendingFile writeFile(DataSnapshot snapshot, ExportFormat format, String audience, Selection selection,
                                         java.util.function.Predicate<Map<String, Object>> keep);
}
