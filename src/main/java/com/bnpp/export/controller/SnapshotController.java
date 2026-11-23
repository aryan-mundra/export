package com.bnpp.export.controller;

import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.model.DataSnapshot;
import com.bnpp.export.model.ExportJobSubscriberStatus;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.DataSnapshotRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.QualityChecker;
import com.bnpp.export.service.SnapshotService;
import com.bnpp.export.service.StorageService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Data versions: list, inspect, compare and download. Everything here reads the stored rows; the
 * source tables are never queried.
 */
@RestController
@RequestMapping("/api/data-versions")
public class SnapshotController {

    private final SnapshotService snapshots;
    private final DataSnapshotRepository repository;
    private final ExportJobSubscriberStatusRepository statuses;
    private final SubscriberRepository subscribers;
    private final FormatTemplateService templates;
    private final QualityChecker qualityChecker;
    private final AuditService audit;
    private final Map<String, ExportFormat> formats;

    public SnapshotController(SnapshotService snapshots,
                              DataSnapshotRepository repository,
                              ExportJobSubscriberStatusRepository statuses,
                              SubscriberRepository subscribers,
                              FormatTemplateService templates,
                              QualityChecker qualityChecker,
                              AuditService audit,
                              List<ExportFormat> formatList) {
        this.snapshots = snapshots;
        this.repository = repository;
        this.statuses = statuses;
        this.subscribers = subscribers;
        this.templates = templates;
        this.qualityChecker = qualityChecker;
        this.audit = audit;
        this.formats = formatList.stream().collect(Collectors.toMap(format -> format.formatType().toUpperCase(Locale.ROOT), format -> format));
    }

    /** The newest data versions (at most 200), optionally of one template. Screens use the paged endpoints below. */
    @GetMapping
    public List<DataSnapshot> list(@RequestParam(required = false) Long familyId) {
        return familyId == null ? repository.findTop200ByOrderByIdDesc() : repository.findTop200ByFamilyIdOrderByIdDesc(familyId);
    }

    /** Templates and lists that have data versions: one catalog row each, searched and paged. No version is read. */
    @GetMapping("/series")
    public SnapshotService.SeriesPage series(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) Long familyId,
                                             @RequestParam(defaultValue = "recent") String sort,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "50") int limit) {
        return snapshots.series(q, familyId, sort, offset, limit);
    }

    /** The versions of one template and list, newest first, a page at a time. */
    @GetMapping("/series/{familyId}/{listCode}")
    public SnapshotService.VersionPage versions(@PathVariable Long familyId,
                                                @PathVariable String listCode,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "25") int limit) {
        return snapshots.versions(familyId, listCode, offset, limit);
    }

    public record Usage(Long jobId, Long statusId, String subscriberName, String formatType, String outcome, String status,
                        int records, String fileName, LocalDateTime at) {
    }

    /** @param usage the 50 most recent files built from this version; usageTotal counts all of them */
    public record Detail(DataSnapshot snapshot, List<Map<String, String>> columns, List<String> filters, List<Usage> usage,
                         long usageTotal) {
    }

    /** A version with its column layout, the row filters of the template version that built it, and where it was used. */
    @GetMapping("/{id}")
    public Detail detail(@PathVariable Long id) {
        DataSnapshot snapshot = snapshots.get(id);
        List<Usage> usage = statuses.findBySnapshotIdOrderByIdDesc(id, org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .map(this::usage)
                .toList();
        return new Detail(snapshot, snapshots.columns(snapshot), templates.filterSummaries(snapshot.getTemplateId()), usage,
                statuses.countBySnapshotId(id));
    }

    private Usage usage(ExportJobSubscriberStatus status) {
        return new Usage(status.getExportJobId(), status.getId(), status.getSubscriberName(), status.getFormatType(),
                status.getSnapshotOutcome(), status.getStatus(), status.getRecordsProcessed(),
                status.getFilePath() == null ? null : Path.of(status.getFilePath()).getFileName().toString(),
                status.getCompletedAt() != null ? status.getCompletedAt() : status.getStartedAt());
    }

    public record Rows(List<Map<String, String>> columns, long total, int offset, List<SnapshotService.PageRow> rows) {
    }

    @GetMapping("/{id}/rows")
    public Rows rows(@PathVariable Long id,
                     @RequestParam(defaultValue = "0") int offset,
                     @RequestParam(defaultValue = "100") int limit) {
        DataSnapshot snapshot = snapshots.get(id);
        return new Rows(snapshots.columns(snapshot), snapshot.getRowCount(), Math.max(0, offset), snapshots.rows(snapshot, offset, limit));
    }

    /** What changed from {@code against} to {@code id}, with the changed records a page at a time. */
    @GetMapping("/{id}/compare/{against}")
    public SnapshotService.Comparison compare(@PathVariable Long id,
                                              @PathVariable Long against,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "50") int limit) {
        return snapshots.compare(id, against, offset, limit);
    }

    /**
     * A version as a file in any supported format, built from the stored rows. With a subscriber, only
     * the rows it receives (its scope, business line and region) that pass the quality check.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "CSV") String format,
                                                      @RequestParam(required = false) Long subscriberId) {
        DataSnapshot snapshot = snapshots.get(id);
        ExportFormat writer = formats.get(StringUtils.trimToEmpty(format).toUpperCase(Locale.ROOT));
        if (writer == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Format must be one of " + String.join(", ", formats.keySet()));
        }
        SnapshotService.Selection selection = SnapshotService.Selection.ALL;
        Predicate<Map<String, Object>> keep = null;
        String audience = null;
        if (subscriberId != null) {
            SubscriberProfile subscriber = subscribers.findById(subscriberId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscriber not found"));
            selection = new SnapshotService.Selection(subscriber.getScope(), "FULL", null, null);
            List<String> required = qualityChecker.columnsForRun(List.of());
            keep = record -> profileMatch(subscriber, record) && qualityChecker.validateRecord(record, required) == null;
            audience = subscriber.getName();
        }
        StorageService.PendingFile pending = snapshots.writeFile(snapshot, writer, audience, selection, keep);
        audit.snapshot(snapshot.getId(), "DATA_VERSION_DOWNLOADED", "Data version " + snapshot.getLabel() + " as "
                + writer.formatType() + (audience == null ? ", all rows" : ", for " + audience));
        Path file = pending.temp();
        StreamingResponseBody body = out -> {
            try {
                Files.copy(file, out);
            } finally {
                Files.deleteIfExists(file);
            }
        };
        MediaType type = "CSV".equals(writer.formatType()) ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_XML;
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pending.fileName() + "\"")
                    .contentLength(Files.size(file))
                    .contentType(type)
                    .body(body);
        } catch (IOException ex) {
            throw new ResponseStatusException(NOT_FOUND, "The file could not be prepared");
        }
    }

    /** The same rule an export uses for a subscriber's business line and region. */
    private static boolean profileMatch(SubscriberProfile subscriber, Map<String, Object> record) {
        if (StringUtils.isNotBlank(subscriber.getBusinessLine()) && record.containsKey("businessLine")
                && !subscriber.getBusinessLine().equalsIgnoreCase(String.valueOf(record.get("businessLine")))) {
            return false;
        }
        return StringUtils.isBlank(subscriber.getRegion())
                || !record.containsKey("region")
                || subscriber.getRegion().equalsIgnoreCase(String.valueOf(record.get("region")));
    }
}
