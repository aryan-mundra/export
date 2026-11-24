package com.bnpp.export.service.impl;

import com.bnpp.export.dto.CertifiedVersionResponse;
import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.TriggerExportRequest;
import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.format.RecordBatchProvider;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.ExportReviewService;
import com.bnpp.export.service.ExportRunService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.QualityCheckService;
import com.bnpp.export.service.RecordSelector;
import com.bnpp.export.service.RowFilterService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bnpp.export.service.impl.ExportSupport.STREAM_BATCH;
import static com.bnpp.export.service.impl.ExportSupport.noTemplate;
import static com.bnpp.export.service.impl.ExportSupport.onList;
import static com.bnpp.export.service.impl.ExportSupport.toInt;

/** Implementation of {@link ExportReviewService}. */
@Service
public class ExportReviewServiceImpl implements ExportReviewService {

    private final ExportSupport support;
    private final ExportRunService runService;
    private final ExportJobRepository exportJobRepository;
    private final CertifiedVersionRepository certifiedVersionRepository;
    private final RecordBatchProvider recordBatchProvider;
    private final QualityCheckService qualityChecker;
    private final FormatTemplateService formatTemplateService;
    private final RowFilterService rowFilters;

    public ExportReviewServiceImpl(ExportSupport support,
                                   ExportRunService runService,
                                   ExportJobRepository exportJobRepository,
                                   CertifiedVersionRepository certifiedVersionRepository,
                                   RecordBatchProvider recordBatchProvider,
                                   QualityCheckService qualityChecker,
                                   FormatTemplateService formatTemplateService,
                                   RowFilterService rowFilters) {
        this.support = support;
        this.runService = runService;
        this.exportJobRepository = exportJobRepository;
        this.certifiedVersionRepository = certifiedVersionRepository;
        this.recordBatchProvider = recordBatchProvider;
        this.qualityChecker = qualityChecker;
        this.formatTemplateService = formatTemplateService;
        this.rowFilters = rowFilters;
    }

    @Override
    public ExportPreview previewExport(TriggerExportRequest request) {
        checkDates(request);
        return preview(request.versionId(), request.fromDate(), request.toDate(), request.subscriberIds());
    }

    @Override
    public ExportJobResponse startExport(TriggerExportRequest request) {
        ExportPreview review = previewExport(request);
        if (request.reviewToken() == null || !request.reviewToken().equals(review.reviewToken())) {
            throw new ConflictException("The review is out of date. Review the export again.");
        }
        return ExportJobResponse.from(triggerExport(request.versionId(), CurrentUser.displayName(), request.fromDate(),
                request.toDate(), request.subscriberIds()));
    }

    /** Both dates or neither, in order. */
    private static void checkDates(TriggerExportRequest request) {
        if ((request.fromDate() == null) != (request.toDate() == null)) {
            throw new BadRequestException("Enter both a from date and a to date, or leave both empty");
        }
        if (request.fromDate() != null && request.fromDate().isAfter(request.toDate())) {
            throw new BadRequestException("From date must be on or before the to date");
        }
    }

    @Override
    public ExportPreview preview(Long versionId, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds) {
        CertifiedVersion version = support.requireVersion(versionId);
        List<String> columns = support.decisionColumns();
        List<ExportPreview.SubscriberPreview> rows = new ArrayList<>();
        for (SubscriberProfile subscriber : support.chosenSubscribers(subscriberIds)) {
            rows.add(previewSubscriber(version, subscriber, fromDate, toDate, columns));
        }
        return new ExportPreview(
                version.getVersionId(),
                version.getListCode(),
                version.getListName(),
                version.getContentAsOf(),
                fromDate,
                toDate,
                true,
                reviewToken(version, fromDate, toDate, rows),
                rows);
    }

    @Override
    public ExportJob triggerExport(Long versionId, String triggeredBy, LocalDate fromDate, LocalDate toDate, List<Long> subscriberIds) {
        CertifiedVersion version = support.requireVersion(versionId);
        List<SubscriberProfile> subscribers = support.chosenSubscribers(subscriberIds);
        ExportJob job = new ExportJob();
        job.setVersionId(versionId);
        job.setFromDate(fromDate);
        job.setToDate(toDate);
        job.setListCode(version.getListCode());
        job.setListName(version.getListName());
        job.setTriggeredBy(StringUtils.defaultIfBlank(triggeredBy, CurrentUser.displayName()));
        job.setMode("EXPORT");
        if (subscriberIds != null && !subscriberIds.isEmpty()) {
            job.setSubscriberIds(String.join(",", subscribers.stream().map(item -> String.valueOf(item.getId())).toList()));
        }
        job.setStatus("PENDING");
        job.setTotalSubscribers(subscribers.size());
        job.setCompletedSubscribers(0);
        job.setFailedSubscribers(0);
        job.setHeldSubscribers(0);
        job.setSkippedSubscribers(0);
        job.setTotalRecordsProcessed(0);
        job.setTotalRecordsQuarantined(0);
        job = exportJobRepository.save(job);
        support.audit(job.getId(), null, "EXPORT_QUEUED",
                version.getListName() + " version " + versionId + " queued for " + subscribers.size() + " subscribers");
        runService.runExport(job.getId()); // through the proxy: runs in the background
        return job;
    }

    @Override
    public List<CertifiedVersionResponse> versions() {
        return certifiedVersionRepository.findAllByOrderByVersionIdAsc().stream()
                .filter(version -> version.isOperatorVisible() && "CERTIFIED".equals(version.getStatus()))
                .map(CertifiedVersionResponse::from)
                .toList();
    }

    private ExportPreview.SubscriberPreview previewSubscriber(CertifiedVersion version,
                                                              SubscriberProfile subscriber,
                                                              LocalDate fromDate,
                                                              LocalDate toDate,
                                                              List<String> columns) {
        boolean included = onList(subscriber, version.getListCode());
        boolean datesApplied = included && "DELTA".equalsIgnoreCase(subscriber.getExportMode()) && fromDate != null;
        if (!included) {
            return previewRow(subscriber, false, false, 0, 0,
                    "Not on the " + version.getListName() + " list");
        }
        FormatTemplate template = formatTemplateService.inUse(subscriber.getTemplateFamilyId()).orElse(null);
        RowFilterService.Condition rowFilter = template == null ? RowFilterService.Condition.NONE
                : rowFilters.condition(formatTemplateService.filters(template.getId()), "e");
        RecordSelector.Counts counts = countFor(version, subscriber, fromDate, toDate, columns, rowFilter);
        String outcome;
        if (template == null) {
            outcome = "Will fail: " + noTemplate(subscriber);
        } else if (counts.rejected() > 0) {
            outcome = "Held until omissions are accepted";
        } else if (counts.valid() == 0 && datesApplied) {
            outcome = "Empty delta window. Nothing will be sent";
        } else if ("DELTA".equalsIgnoreCase(subscriber.getExportMode())) {
            outcome = datesApplied ? "Delta file for the date window" : "Delta file: every changed record";
        } else {
            outcome = "Full file";
        }
        return previewRow(subscriber, true, datesApplied, toInt(counts.valid()), toInt(counts.rejected()), outcome);
    }

    private ExportPreview.SubscriberPreview previewRow(SubscriberProfile subscriber,
                                                        boolean included,
                                                        boolean datesApplied,
                                                        int records,
                                                        int quarantined,
                                                        String outcome) {
        FormatTemplate template = formatTemplateService.inUse(subscriber.getTemplateFamilyId()).orElse(null);
        return new ExportPreview.SubscriberPreview(
                subscriber.getId(),
                subscriber.getName(),
                subscriber.getFormatType(),
                subscriber.getExportMode(),
                subscriber.getScope(),
                subscriber.getBusinessLine(),
                subscriber.getRegion(),
                template == null ? null : template.getName(),
                template == null ? null : template.getVersionNumber(),
                template == null ? List.of() : formatTemplateService.filterSummaries(template.getId()),
                included,
                datesApplied,
                records,
                quarantined,
                outcome);
    }

    /**
     * What a subscriber would receive, for the review step: counted in the database when the reader
     * can do that, otherwise by streaming the records and counting them (still without keeping them).
     */
    private RecordSelector.Counts countFor(CertifiedVersion version,
                                           SubscriberProfile subscriber,
                                           LocalDate fromDate,
                                           LocalDate toDate,
                                           List<String> columns,
                                           RowFilterService.Condition rowFilter) {
        RecordSelector.CountRequest request = new RecordSelector.CountRequest(
                version.getVersionId(), subscriber.getScope(), subscriber.getExportMode(), fromDate, toDate,
                subscriber.getBusinessLine(), subscriber.getRegion(), columns, rowFilter);
        return recordBatchProvider.count(request).orElseGet(() -> {
            long[] counts = new long[2];
            recordBatchProvider.stream(version.getVersionId(), subscriber.getScope(), subscriber.getExportMode(),
                    fromDate, toDate, STREAM_BATCH, batch -> {
                        List<Map<String, Object>> matching = batch.stream()
                                .filter(record -> subscriber.receives(record))
                                .toList();
                        for (Map<String, Object> record : rowFilters.keep(version.getVersionId(), matching, rowFilter)) {
                            counts[qualityChecker.validateRecord(record, columns) == null ? 0 : 1]++;
                        }
                    });
            return new RecordSelector.Counts(counts[0], counts[1]);
        });
    }

    /** A hash of everything the user reviewed: the export is refused if any of it changed since. */
    private static String reviewToken(CertifiedVersion version,
                                      LocalDate fromDate,
                                      LocalDate toDate,
                                      List<ExportPreview.SubscriberPreview> rows) {
        StringBuilder raw = new StringBuilder();
        raw.append(version.getVersionId()).append('|')
                .append(fromDate).append('|')
                .append(toDate);
        for (ExportPreview.SubscriberPreview row : rows) {
            raw.append('|').append(row.subscriberId())
                    .append(':').append(row.included())
                    .append(':').append(row.records())
                    .append(':').append(row.quarantined())
                    .append(':').append(row.formatType())
                    .append(':').append(row.exportMode())
                    .append(':').append(row.scope())
                    .append(':').append(row.templateName())
                    .append(':').append(row.templateVersion());
        }
        return ExportSupport.sha256(raw.toString());
    }
}
