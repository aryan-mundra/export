package com.bnpp.export.service.impl;

import com.bnpp.export.dto.ListPageResponse;
import com.bnpp.export.dto.SubscriberPageResponse;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.ListCatalogService;
import com.bnpp.export.util.SqlUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Implementation of {@link ListCatalogService}. */
@Service
public class ListCatalogServiceImpl implements ListCatalogService {

    private final JdbcTemplate jdbc;
    private final SubscriberRepository subscribers;

    public ListCatalogServiceImpl(JdbcTemplate jdbc, SubscriberRepository subscribers) {
        this.jdbc = jdbc;
        this.subscribers = subscribers;
    }

    @Override
    public ListPageResponse lists(String query, String sort, int offset, int limit) {
        String visible = "status = 'CERTIFIED' AND operator_visible = TRUE";
        StringBuilder where = new StringBuilder(" FROM ver_version v WHERE v." + visible.replace(" AND ", " AND v.")
                + " AND v.version_id = (SELECT MAX(w.version_id) FROM ver_version w WHERE w.list_code = v.list_code AND w."
                + visible.replace(" AND ", " AND w.") + ")");
        List<Object> args = new ArrayList<>();
        String text = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (!text.isEmpty()) {
            where.append(" AND (UPPER(v.list_name) LIKE ? ESCAPE '\\' OR UPPER(v.list_code) LIKE ? ESCAPE '\\')");
            String like = "%" + SqlUtils.likeEscape(text) + "%";
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
        List<ListPageResponse.ListSummary> rows = jdbc.query("SELECT v.version_id, v.list_code, v.list_name, v.content_as_of, "
                        + "(SELECT COUNT(*) FROM ver_version c WHERE c.list_code = v.list_code AND c." + visible.replace(" AND ", " AND c.") + ") AS versions, "
                        + "(SELECT MAX(j.created_at) FROM ver_export_job j WHERE j.list_code = v.list_code) AS last_export"
                        + where + order + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, index) -> new ListPageResponse.ListSummary(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getLong(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime()),
                pageArgs.toArray());
        return new ListPageResponse(total == null ? 0 : total, Math.max(0, offset), rows);
    }

    @Override
    public SubscriberPageResponse subscribers(String query, String format, Long templateFamilyId, int offset, int limit) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SubscriberProfile> matching = subscribers.findByActiveTrueOrderByIdAsc().stream()
                .filter(item -> format == null || format.isBlank() || hasFormat(item, format))
                .filter(item -> templateFamilyId == null || templateFamilyId.equals(item.getTemplateFamilyId()))
                .filter(item -> text.isEmpty() || contains(item.getName(), text) || contains(item.getBusinessLine(), text)
                        || contains(item.getRegion(), text) || contains(item.getScope(), text))
                .sorted(java.util.Comparator.comparing(item -> item.getName().toLowerCase(Locale.ROOT)))
                .toList();
        int start = Math.min(matching.size(), Math.max(0, offset));
        int end = Math.min(matching.size(), start + Math.min(100, Math.max(1, limit)));
        List<SubscriberPageResponse.Row> page = matching.subList(start, end).stream()
                .map(item -> new SubscriberPageResponse.Row(item.getId(), item.getName(), item.getFormatType(), item.getScope(),
                        item.getExportMode(), item.getBusinessLine(), item.getRegion(), item.getTemplateFamilyId(), item.isActive()))
                .toList();
        return new SubscriberPageResponse(matching.size(), start, page);
    }

    private static boolean hasFormat(SubscriberProfile item, String format) {
        if (item.getFormatType() == null) {
            return false;
        }
        for (String part : item.getFormatType().split(",")) {
            if (part.trim().equalsIgnoreCase(format)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String text) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(text);
    }
}
