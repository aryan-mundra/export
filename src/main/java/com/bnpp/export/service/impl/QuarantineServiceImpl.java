package com.bnpp.export.service.impl;

import com.bnpp.export.dto.QuarantineResponse;
import com.bnpp.export.entity.QuarantineRecord;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.QuarantineService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Implementation of {@link QuarantineService}. */
@Service
public class QuarantineServiceImpl implements QuarantineService {

    private final QuarantineRepository quarantineRepository;
    private final SubscriberRepository subscriberRepository;

    public QuarantineServiceImpl(QuarantineRepository quarantineRepository, SubscriberRepository subscriberRepository) {
        this.quarantineRepository = quarantineRepository;
        this.subscriberRepository = subscriberRepository;
    }

    @Override
    public List<QuarantineResponse> search(String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> names = subscriberRepository.findAll().stream()
                .collect(Collectors.toMap(SubscriberProfile::getId, SubscriberProfile::getName));
        return quarantineRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(record -> text.isEmpty() || matches(record, names.get(record.getSubscriberId()), text))
                .map(record -> new QuarantineResponse(
                        record.getId(),
                        record.getVersionId(),
                        record.getExportJobId(),
                        record.getSubscriberId(),
                        names.getOrDefault(record.getSubscriberId(), "Subscriber " + record.getSubscriberId()),
                        record.getRecordIdentifier(),
                        record.getQuarantineReason(),
                        record.isPatchApplied(),
                        record.getAcceptedBy(),
                        record.getAcceptedAt(),
                        record.getCreatedAt()))
                .toList();
    }

    private static boolean matches(QuarantineRecord record, String subscriberName, String query) {
        return contains(record.getRecordIdentifier(), query)
                || contains(record.getQuarantineReason(), query)
                || contains(subscriberName, query)
                || String.valueOf(record.getVersionId()).contains(query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
