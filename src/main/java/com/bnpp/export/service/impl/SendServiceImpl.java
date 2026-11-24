package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.SendRequest;
import com.bnpp.export.dto.SendReviewResponse;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.model.DataVersion;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.DataVersionService;
import com.bnpp.export.service.ExportRunService;
import com.bnpp.export.service.SendService;
import com.bnpp.export.util.Hashing;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Implementation of {@link SendService}. */
@Service
public class SendServiceImpl implements SendService {

    private final DataVersionService dataVersions;
    private final ExportSupport support;
    private final SubscriberRepository subscribers;
    private final ExportJobRepository jobs;
    private final ExportRunService runService;
    private final AuditService audit;

    public SendServiceImpl(DataVersionService dataVersions, ExportSupport support, SubscriberRepository subscribers,
                           ExportJobRepository jobs, ExportRunService runService, AuditService audit) {
        this.dataVersions = dataVersions;
        this.support = support;
        this.subscribers = subscribers;
        this.jobs = jobs;
        this.runService = runService;
        this.audit = audit;
    }

    @Override
    public SendReviewResponse review(Long dataVersionId, List<Long> subscriberIds) {
        DataVersion version = dataVersions.get(dataVersionId);
        List<Long> onList = subscribers.findByActiveTrueOrderByIdAsc().stream()
                .filter(subscriber -> version.familyId().equals(subscriber.getTemplateFamilyId()))
                .map(SubscriberProfile::getId)
                .toList();
        List<Long> chosen = subscriberIds == null || subscriberIds.isEmpty() ? onList : new ArrayList<>(new TreeSet<>(subscriberIds));
        List<SendReviewResponse.Row> rows = new ArrayList<>();
        for (Long id : chosen) {
            SubscriberProfile subscriber = support.requireSubscriber(id);
            if (!subscriber.isActive()) {
                throw new BadRequestException(subscriber.getName() + " is not active");
            }
            rows.add(new SendReviewResponse.Row(subscriber.getId(), subscriber.getName(), version.records(), subscriber.getCftEndpoint(),
                    version.familyId().equals(subscriber.getTemplateFamilyId())));
        }
        StringBuilder raw = new StringBuilder("send|" + version.id() + "|" + version.number() + "|" + version.records());
        rows.forEach(row -> raw.append('|').append(row.subscriberId()).append(':').append(row.endpoint()));
        return new SendReviewResponse(DataVersionResponse.from(version), rows, onList, Hashing.sha256(raw.toString()));
    }

    @Override
    public ExportJobResponse send(Long dataVersionId, SendRequest request) {
        SendReviewResponse review = review(dataVersionId, request.subscriberIds());
        if (request.reviewToken() == null || !request.reviewToken().equals(review.reviewToken())) {
            throw new ConflictException("The review is out of date. Review the send again.");
        }
        if (review.subscribers().isEmpty()) {
            throw new BadRequestException("Choose at least one subscriber");
        }
        DataVersionResponse version = review.version();
        ExportJob job = new ExportJob();
        job.setFamilyId(version.familyId());
        job.setListName(version.listName());
        job.setDataVersionId(version.id());
        job.setDataVersionNumber(version.number());
        job.setFormatTemplateId(version.templateId());
        job.setFormatTemplateVersion(version.templateVersion());
        job.setSubscriberIds(String.join(",", review.subscribers().stream().map(row -> String.valueOf(row.subscriberId())).toList()));
        job.setTriggeredBy(CurrentUser.displayName());
        job.setStatus("PENDING");
        job.setTotalSubscribers(review.subscribers().size());
        job = jobs.save(job);
        audit.job(job.getId(), null, "EXPORT_QUEUED", version.listName() + " data version " + version.number() + " (" + version.records()
                + " records) queued for " + String.join(", ", review.subscribers().stream().map(SendReviewResponse.Row::name).toList()));
        runService.run(job.getId()); // through the proxy: runs in the background
        return ExportJobResponse.from(job);
    }
}
