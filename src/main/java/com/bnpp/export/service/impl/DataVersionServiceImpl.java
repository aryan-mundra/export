package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DataVersionComparisonResponse;
import com.bnpp.export.dto.DataVersionDetailResponse;
import com.bnpp.export.dto.DataVersionPageResponse;
import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.DataVersionRowsResponse;
import com.bnpp.export.dto.DataVersionSeriesPageResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.repository.DataSnapshotRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.DataVersionService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.QualityCheckService;
import com.bnpp.export.service.SnapshotService;
import com.bnpp.export.service.StorageService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Implementation of {@link DataVersionService}. */
@Service
public class DataVersionServiceImpl implements DataVersionService {

    private static final int RECENT_USAGE = 50;

    private final SnapshotService snapshots;
    private final DataSnapshotRepository repository;
    private final ExportJobSubscriberStatusRepository statuses;
    private final SubscriberRepository subscribers;
    private final FormatTemplateService templates;
    private final QualityCheckService qualityCheck;
    private final AuditService audit;
    private final Map<String, ExportFormat> formats;

    public DataVersionServiceImpl(SnapshotService snapshots,
                                  DataSnapshotRepository repository,
                                  ExportJobSubscriberStatusRepository statuses,
                                  SubscriberRepository subscribers,
                                  FormatTemplateService templates,
                                  QualityCheckService qualityCheck,
                                  AuditService audit,
                                  List<ExportFormat> formatList) {
        this.snapshots = snapshots;
        this.repository = repository;
        this.statuses = statuses;
        this.subscribers = subscribers;
        this.templates = templates;
        this.qualityCheck = qualityCheck;
        this.audit = audit;
        this.formats = formatList.stream().collect(Collectors.toMap(format -> format.formatType().toUpperCase(Locale.ROOT), format -> format));
    }

    @Override
    public List<DataVersionResponse> latest(Long familyId) {
        List<DataSnapshot> found = familyId == null ? repository.findTop200ByOrderByIdDesc() : repository.findTop200ByFamilyIdOrderByIdDesc(familyId);
        return found.stream().map(DataVersionResponse::from).toList();
    }

    @Override
    public DataVersionSeriesPageResponse series(String query, Long familyId, String sort, int offset, int limit) {
        return snapshots.series(query, familyId, sort, offset, limit);
    }

    @Override
    public DataVersionPageResponse versions(Long familyId, String listCode, int offset, int limit) {
        return snapshots.versions(familyId, listCode, offset, limit);
    }

    @Override
    public DataVersionDetailResponse detail(Long id) {
        DataSnapshot snapshot = snapshots.get(id);
        List<DataVersionDetailResponse.Usage> usage = statuses.findBySnapshotIdOrderByIdDesc(id, PageRequest.of(0, RECENT_USAGE)).stream()
                .map(DataVersionServiceImpl::usage)
                .toList();
        return new DataVersionDetailResponse(DataVersionResponse.from(snapshot), snapshots.columns(snapshot),
                templates.filterSummaries(snapshot.getTemplateId()), usage, statuses.countBySnapshotId(id));
    }

    private static DataVersionDetailResponse.Usage usage(ExportJobSubscriberStatus status) {
        return new DataVersionDetailResponse.Usage(status.getExportJobId(), status.getId(), status.getSubscriberName(), status.getFormatType(),
                status.getSnapshotOutcome(), status.getStatus(), status.getRecordsProcessed(),
                status.getFilePath() == null ? null : Path.of(status.getFilePath()).getFileName().toString(),
                status.getCompletedAt() != null ? status.getCompletedAt() : status.getStartedAt());
    }

    @Override
    public DataVersionRowsResponse rows(Long id, int offset, int limit) {
        DataSnapshot snapshot = snapshots.get(id);
        return new DataVersionRowsResponse(snapshots.columns(snapshot), snapshot.getRowCount(), Math.max(0, offset),
                snapshots.rows(snapshot, offset, limit));
    }

    @Override
    public DataVersionComparisonResponse compare(Long id, Long against, int offset, int limit) {
        return snapshots.compare(id, against, offset, limit);
    }

    @Override
    public FileDownload download(Long id, String format, Long subscriberId) {
        DataSnapshot snapshot = snapshots.get(id);
        ExportFormat writer = formats.get(StringUtils.trimToEmpty(format).toUpperCase(Locale.ROOT));
        if (writer == null) {
            throw new BadRequestException("Format must be one of " + String.join(", ", formats.keySet()));
        }
        SnapshotService.Selection selection = SnapshotService.Selection.ALL;
        Predicate<Map<String, Object>> keep = null;
        String audience = null;
        if (subscriberId != null) {
            SubscriberProfile subscriber = subscribers.findById(subscriberId)
                    .orElseThrow(() -> new ResourceNotFoundException("Subscriber not found"));
            selection = new SnapshotService.Selection(subscriber.getScope(), "FULL", null, null);
            List<String> required = qualityCheck.columnsForRun(List.of());
            keep = record -> subscriber.receives(record) && qualityCheck.validateRecord(record, required) == null;
            audience = subscriber.getName();
        }
        StorageService.PendingFile pending = snapshots.writeFile(snapshot, writer, audience, selection, keep);
        audit.snapshot(snapshot.getId(), "DATA_VERSION_DOWNLOADED", "Data version " + snapshot.getLabel() + " as "
                + writer.formatType() + (audience == null ? ", all rows" : ", for " + audience));
        return FileDownload.temporary(pending.fileName(), pending.temp());
    }
}
