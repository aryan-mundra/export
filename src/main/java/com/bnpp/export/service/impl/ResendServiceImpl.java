package com.bnpp.export.service.impl;

import com.bnpp.export.dto.ResendPreviewResponse;
import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.entity.DataSnapshot;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.ExportRunService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.QualityCheckService;
import com.bnpp.export.service.ResendService;
import com.bnpp.export.service.SnapshotService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Implementation of {@link ResendService}. */
@Service
public class ResendServiceImpl implements ResendService {

    private final ExportSupport support;
    private final ExportRunService runService;
    private final SubscriberRepository subscriberRepository;
    private final ExportJobRepository exportJobRepository;
    private final SnapshotService snapshotService;
    private final FormatTemplateService formatTemplateService;
    private final QualityCheckService qualityChecker;

    public ResendServiceImpl(ExportSupport support,
                             ExportRunService runService,
                             SubscriberRepository subscriberRepository,
                             ExportJobRepository exportJobRepository,
                             SnapshotService snapshotService,
                             FormatTemplateService formatTemplateService,
                             QualityCheckService qualityChecker) {
        this.support = support;
        this.runService = runService;
        this.subscriberRepository = subscriberRepository;
        this.exportJobRepository = exportJobRepository;
        this.snapshotService = snapshotService;
        this.formatTemplateService = formatTemplateService;
        this.qualityChecker = qualityChecker;
    }

    @Override
    public ResendPreviewResponse previewResend(Long snapshotId, List<Long> subscriberIds, String formatType, Map<Long, String> perSubscriber) {
        DataSnapshot snapshot = snapshotService.get(snapshotId);
        String format = support.sendFormat(formatType);
        Map<Long, List<String>> chosenFormats = new HashMap<>();
        if (perSubscriber != null) {
            perSubscriber.forEach((id, value) -> {
                List<String> many = support.sendFormats(value);
                if (id != null && !many.isEmpty()) {
                    chosenFormats.put(id, many);
                }
            });
        }
        if (subscriberIds == null || subscriberIds.isEmpty()) {
            // Every active subscriber on the version's template.
            subscriberIds = subscriberRepository.findByActiveTrueOrderByIdAsc().stream()
                    .filter(item -> snapshot.getFamilyId().equals(item.getTemplateFamilyId()))
                    .map(SubscriberProfile::getId)
                    .toList();
            if (subscriberIds.isEmpty()) {
                throw new BadRequestException("No active subscriber uses this list");
            }
        }
        List<FormatField> fields = formatTemplateService.fields(snapshot.getTemplateId());
        List<String> columns = support.decisionColumns();
        List<ResendPreviewResponse.Subscriber> rows = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(subscriberIds)) {
            SubscriberProfile subscriber = support.requireSubscriber(id);
            if (!subscriber.isActive()) {
                throw new BadRequestException(subscriber.getName() + " is not active");
            }
            long[] counts = new long[2];
            snapshotService.stream(snapshot, new SnapshotService.Selection(subscriber.getScope(), subscriber.getExportMode(), null, null),
                    fields, record -> {
                        if (subscriber.receives(record)) {
                            counts[qualityChecker.validateRecord(record, columns) == null ? 0 : 1]++;
                        }
                    });
            String outcome = counts[1] > 0 ? "Held until omissions are accepted"
                    : counts[0] == 0 ? "Nothing to send for this subscriber" : "Ready to send";
            for (String rowFormat : formatsFor(subscriber, format, chosenFormats)) {
                rows.add(new ResendPreviewResponse.Subscriber(subscriber.getId(), subscriber.getName(), rowFormat, subscriber.getScope(),
                        subscriber.getExportMode(), counts[0], counts[1], outcome));
            }
        }
        StringBuilder raw = new StringBuilder("resend|" + snapshot.getId() + "|" + snapshot.getFingerprint() + "|" + format);
        rows.forEach(row -> raw.append('|').append(row.subscriberId()).append(':').append(row.formatType()).append(':')
                .append(row.scope()).append(':').append(row.exportMode()).append(':').append(row.records()).append(':').append(row.quarantined()));
        return new ResendPreviewResponse(snapshot.getId(), snapshot.getLabel(), snapshot.getTemplateName(), snapshot.getListName(),
                snapshot.getRowCount(), ExportSupport.sha256(raw.toString()), rows);
    }

    @Override
    public ExportJob triggerResend(Long snapshotId, List<Long> subscriberIds, String reviewToken, String formatType,
                                   Map<Long, String> perSubscriber) {
        ResendPreviewResponse preview = previewResend(snapshotId, subscriberIds, formatType, perSubscriber);
        if (reviewToken == null || !reviewToken.equals(preview.reviewToken())) {
            throw new ConflictException("The review is out of date. Review the resend again.");
        }
        DataSnapshot snapshot = snapshotService.get(snapshotId);
        CertifiedVersion version = support.requireVersion(snapshot.getSourceVersionId());
        ExportJob job = new ExportJob();
        job.setVersionId(version.getVersionId());
        job.setListCode(version.getListCode());
        job.setListName(version.getListName());
        job.setMode("RESEND");
        job.setSnapshotId(snapshot.getId());
        job.setSnapshotLabel(snapshot.getLabel());
        job.setSnapshotTemplate(snapshot.getTemplateName());
        // One entry per file: "id:FORMAT". The same subscriber is listed again for each extra format.
        job.setSubscriberIds(String.join(",", preview.subscribers().stream().map(row -> row.subscriberId() + ":" + row.formatType()).toList()));
        job.setTriggeredBy(CurrentUser.displayName());
        job.setStatus("PENDING");
        job.setTotalSubscribers(preview.subscribers().size());
        job = exportJobRepository.save(job);
        support.audit(job.getId(), null, "EXPORT_QUEUED", "Resend of " + snapshot.getTemplateName() + " data version " + snapshot.getLabel()
                + " (" + snapshot.getListName() + ") to "
                + String.join(", ", preview.subscribers().stream().map(row -> row.name() + " (" + row.formatType() + ")").toList()));
        runService.runExport(job.getId()); // through the proxy: runs in the background
        return job;
    }

    @Override
    public List<String> formatTypes() {
        return support.formatTypes();
    }

    /** The files one subscriber receives: the formats chosen for them, else the send's format, else their own. */
    private List<String> formatsFor(SubscriberProfile subscriber, String format, Map<Long, List<String>> chosen) {
        List<String> listed = chosen.get(subscriber.getId());
        if (listed != null && !listed.isEmpty()) {
            return listed;
        }
        if (format != null) {
            return List.of(format);
        }
        return support.ownFormats(subscriber);
    }
}
