package com.bnpp.export.service;

import com.bnpp.export.model.CertifiedVersion;
import com.bnpp.export.model.DataSnapshot;
import com.bnpp.export.model.FormatTemplate;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.bnpp.export.repository.SubscriberRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * The Export page works by template: each approved template, its latest data version, and its
 * subscribers. Exporting the latest version reads the current data once: identical data keeps the
 * existing version, changed data becomes the next one. Lists are searched and paged on the server.
 */
@Service
public class TemplateExportService {

    private final FormatTemplateRepository templates;
    private final SubscriberRepository subscribers;
    private final CertifiedVersionRepository versions;
    private final SnapshotService snapshots;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public TemplateExportService(FormatTemplateRepository templates, SubscriberRepository subscribers,
                                 CertifiedVersionRepository versions, SnapshotService snapshots, AuditService audit,
                                 JdbcTemplate jdbc) {
        this.templates = templates;
        this.subscribers = subscribers;
        this.versions = versions;
        this.snapshots = snapshots;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * One approved template.
     *
     * @param latestSnapshotId its newest data version (null when it was never exported); listCode tells which list
     * @param versionCount     data versions it has across all its lists
     */
    public record TemplateRow(Long familyId, Long templateId, String name, int templateVersion,
                              long subscribers, Long latestSnapshotId, String latestLabel, LocalDateTime latestAt,
                              String listCode, String listName, long versionCount, LocalDateTime lastExport) {
    }

    public record TemplatePage(long total, int offset, List<TemplateRow> templates) {
    }

    /**
     * Approved templates in use, searched by name, a page at a time.
     *
     * @param withVersions only templates that already have data versions (for the Previous Versions tab)
     */
    public TemplatePage list(String query, boolean withVersions, int offset, int limit) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<Long, Series> series = series();
        List<FormatTemplate> matching = templates.findAll().stream()
                .filter(FormatTemplate::isActive)
                .filter(template -> text.isEmpty() || template.getName().toLowerCase(Locale.ROOT).contains(text))
                .filter(template -> !withVersions || series.containsKey(template.getFamilyId()))
                .sorted(Comparator.comparing(template -> template.getName().toLowerCase(Locale.ROOT)))
                .toList();
        int start = Math.min(matching.size(), Math.max(0, offset));
        int end = Math.min(matching.size(), start + Math.min(100, Math.max(1, limit)));
        Map<Long, Long> counts = subscriberCounts();
        List<TemplateRow> rows = new ArrayList<>();
        for (FormatTemplate template : matching.subList(start, end)) {
            Series s = series.get(template.getFamilyId());
            rows.add(new TemplateRow(template.getFamilyId(), template.getId(), template.getName(),
                    template.getVersionNumber(), counts.getOrDefault(template.getFamilyId(), 0L),
                    s == null ? null : s.latestId(), s == null ? null : s.latestLabel(), s == null ? null : s.latestAt(),
                    s == null ? null : s.listCode(), s == null ? null : s.listName(), s == null ? 0 : s.versions(),
                    s == null ? null : s.lastUsed()));
        }
        return new TemplatePage(matching.size(), start, rows);
    }

    /** Per template: its most recently used list's latest version, and the versions it has across lists. */
    private record Series(Long latestId, String latestLabel, LocalDateTime latestAt, String listCode, String listName,
                          long versions, LocalDateTime lastUsed) {
    }

    private Map<Long, Series> series() {
        Map<Long, Series> byFamily = new HashMap<>();
        jdbc.query("SELECT family_id, latest_snapshot_id, latest_label, latest_at, list_code, list_name, version_count, last_used_at "
                + "FROM ver_export_snapshot_series WHERE version_count > 0 ORDER BY last_used_at DESC NULLS LAST, id DESC", rs -> {
            Long family = rs.getLong(1);
            Series known = byFamily.get(family);
            long count = rs.getLong(7) + (known == null ? 0 : known.versions());
            if (known == null) {
                byFamily.put(family, new Series(rs.getLong(2), rs.getString(3), toLocal(rs.getTimestamp(4)), rs.getString(5),
                        rs.getString(6), count, toLocal(rs.getTimestamp(8))));
            } else {
                byFamily.put(family, new Series(known.latestId(), known.latestLabel(), known.latestAt(), known.listCode(),
                        known.listName(), count, known.lastUsed()));
            }
        });
        return byFamily;
    }

    private Map<Long, Long> subscriberCounts() {
        Map<Long, Long> counts = new HashMap<>();
        for (SubscriberProfile subscriber : subscribers.findByActiveTrueOrderByIdAsc()) {
            if (subscriber.getTemplateFamilyId() != null) {
                counts.merge(subscriber.getTemplateFamilyId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** The data version an export of the latest data gives, and how it compares with the one before. */
    public record LatestResult(DataSnapshot snapshot, boolean created) {
    }

    /**
     * Reads the template's current data from the newest certified list version: identical data keeps
     * its existing version; changed data becomes the next one (e.g. 1.1, then 1.2).
     */
    public LatestResult exportLatest(Long familyId) {
        FormatTemplate template = templates.findFirstByFamilyIdAndActiveTrueOrderByVersionNumberDesc(familyId)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "This list has no approved version to export"));
        CertifiedVersion version = versions.findAllByOrderByVersionIdAsc().stream()
                .filter(candidate -> candidate.isOperatorVisible() && "CERTIFIED".equals(candidate.getStatus()))
                .max(Comparator.comparing((CertifiedVersion candidate) -> candidate.getContentAsOf() == null
                                ? java.time.LocalDate.MIN : candidate.getContentAsOf())
                        .thenComparing(CertifiedVersion::getVersionId))
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "No certified list version is available"));
        SnapshotService.Resolution resolution = snapshots.resolve(version.getVersionId(), template, null);
        return new LatestResult(resolution.snapshot(), resolution.created());
    }

    private static LocalDateTime toLocal(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
