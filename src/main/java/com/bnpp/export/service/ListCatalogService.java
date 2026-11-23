package com.bnpp.export.service;

import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.SubscriberRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searchable, paged catalogs for the Export page, so it never loads every list or subscriber.
 *
 * Lists: one row per list (its newest certified version operators can export), searched by name or
 * code in the database. Subscribers: searched and paged on the server.
 */
@Service
public class ListCatalogService {

    private final JdbcTemplate jdbc;
    private final SubscriberRepository subscribers;

    public ListCatalogService(JdbcTemplate jdbc, SubscriberRepository subscribers) {
        this.jdbc = jdbc;
        this.subscribers = subscribers;
    }

    /**
     * @param versions   certified versions of this list operators can export
     * @param lastExport when this list was last exported (null if never)
     */
    public record ListSummary(Long versionId, String listCode, String listName, LocalDate contentAsOf,
                              long versions, LocalDateTime lastExport) {
    }

    public record ListPage(long total, int offset, List<ListSummary> lists) {
    }

    /** @param sort "recent" (last exported first, then by name) or "name" */
    public ListPage lists(String query, String sort, int offset, int limit) {
        String visible = "status = 'CERTIFIED' AND operator_visible = TRUE";
        StringBuilder where = new StringBuilder(" FROM ver_version v WHERE v." + visible.replace(" AND ", " AND v.")
                + " AND v.version_id = (SELECT MAX(w.version_id) FROM ver_version w WHERE w.list_code = v.list_code AND w."
                + visible.replace(" AND ", " AND w.") + ")");
        List<Object> args = new ArrayList<>();
        String text = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (!text.isEmpty()) {
            where.append(" AND (UPPER(v.list_name) LIKE ? ESCAPE '\\' OR UPPER(v.list_code) LIKE ? ESCAPE '\\')");
            String like = "%" + RowFilterService.likeEscape(text) + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        String order = "name".equalsIgnoreCase(sort)
                ? " ORDER BY v.list_name, v.list_code"
                : " ORDER BY last_export DESC NULLS LAST, v.list_name, v.list_code";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.max(0, offset));
        pageArgs.add(Math.min(100, Math.max(1, limit)));
        List<ListSummary> rows = jdbc.query("SELECT v.version_id, v.list_code, v.list_name, v.content_as_of, "
                        + "(SELECT COUNT(*) FROM ver_version c WHERE c.list_code = v.list_code AND c." + visible.replace(" AND ", " AND c.") + ") AS versions, "
                        + "(SELECT MAX(j.created_at) FROM ver_export_job j WHERE j.list_code = v.list_code) AS last_export"
                        + where + order + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, index) -> new ListSummary(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getLong(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime()),
                pageArgs.toArray());
        return new ListPage(total == null ? 0 : total, Math.max(0, offset), rows);
    }

    public record SubscriberRow(Long id, String name, String formatType, String scope, String exportMode,
                                String businessLine, String region, Long templateFamilyId, boolean active) {
    }

    public record SubscriberPage(long total, int offset, List<SubscriberRow> subscribers) {
    }

    /**
     * Active subscribers matching a search (name, business line or region), optionally of one format
     * or one template, a page at a time.
     */
    public SubscriberPage subscribers(String query, String format, Long templateFamilyId, int offset, int limit) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SubscriberProfile> matching = subscribers.findByActiveTrueOrderByIdAsc().stream()
                .filter(item -> format == null || format.isBlank() || format.equalsIgnoreCase(item.getFormatType()))
                .filter(item -> templateFamilyId == null || templateFamilyId.equals(item.getTemplateFamilyId()))
                .filter(item -> text.isEmpty() || contains(item.getName(), text) || contains(item.getBusinessLine(), text)
                        || contains(item.getRegion(), text) || contains(item.getScope(), text))
                .sorted(java.util.Comparator.comparing(item -> item.getName().toLowerCase(Locale.ROOT)))
                .toList();
        int start = Math.min(matching.size(), Math.max(0, offset));
        int end = Math.min(matching.size(), start + Math.min(100, Math.max(1, limit)));
        List<SubscriberRow> page = matching.subList(start, end).stream()
                .map(item -> new SubscriberRow(item.getId(), item.getName(), item.getFormatType(), item.getScope(),
                        item.getExportMode(), item.getBusinessLine(), item.getRegion(), item.getTemplateFamilyId(), item.isActive()))
                .toList();
        return new SubscriberPage(matching.size(), start, page);
    }

    private static boolean contains(String value, String text) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(text);
    }
}
