package com.bnpp.export.service.impl;

import com.bnpp.export.dto.QuarantinePageResponse;
import com.bnpp.export.dto.QuarantineResponse;
import com.bnpp.export.entity.QuarantineRecord;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.repository.QuarantineRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.QuarantineService;
import com.bnpp.export.util.OffsetPage;
import com.bnpp.export.util.SqlUtils;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    public QuarantinePageResponse search(String query, int offset, int limit) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> names = subscriberRepository.findAll().stream()
                .collect(Collectors.toMap(SubscriberProfile::getId, SubscriberProfile::getName));
        OffsetPage page = OffsetPage.of(offset, limit);
        Page<QuarantineRecord> found;
        if (text.isEmpty()) {
            found = quarantineRepository.findAllByOrderByCreatedAtDescIdDesc(page);
        } else {
            // The search runs in the database; subscriber names are matched here (a small table).
            List<Long> matchingSubscribers = names.entrySet().stream()
                    .filter(entry -> contains(entry.getValue(), text))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (matchingSubscribers.isEmpty()) {
                matchingSubscribers.add(-1L); // "in ()" is not valid SQL
            }
            found = quarantineRepository.search("%" + SqlUtils.likeEscape(text) + "%", matchingSubscribers, page);
        }
        List<QuarantineResponse> records = found.getContent().stream()
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
        return new QuarantinePageResponse(found.getTotalElements(), (int) page.offset(), records);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
