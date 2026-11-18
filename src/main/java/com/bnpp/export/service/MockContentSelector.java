package com.bnpp.export.service;

import com.bnpp.export.config.MockProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "export.mock.enabled", havingValue = "true")
public class MockContentSelector implements RecordSelector {

    private static final int BATCH_SIZE = 25;
    private static final String[] NAMES = {
            "Amina Diallo", "Hugo Martin", "Priya Nair", "Elena Rossi", "Noah Keller",
            "Sofia Alvarez", "James Okonkwo", "Mei Chen", "Lars Andersen", "Fatima Haddad",
            "Owen Blake", "Chloe Dubois", "Ravi Kapoor", "Nora Berg", "Hassan Ali",
            "Emma Clarke", "Luca Bianchi", "Yuki Tanaka", "Ines Moreau", "Daniel Kruger"
    };
    private static final String[] COUNTRIES = {"FR", "US", "GB", "DE", "IN", "SG", "AE", "CH", "JP", "BR"};
    private static final String[] STATUSES = {"ACTIVE", "INACTIVE", "REVIEW"};
    private static final String[] ACTIONS = {"ADD", "MODIFY", "DELETE"};

    private final MockProperties mockProperties;

    public MockContentSelector(MockProperties mockProperties) {
        this.mockProperties = mockProperties;
    }

    @Override
    public void streamRecords(Long versionId,
                              String scope,
                              String exportMode,
                              LocalDate fromDate,
                              LocalDate toDate,
                              Consumer<List<Map<String, Object>>> consumer) {
        int total = Math.max(0, mockProperties.getRecordsPerSubscriber());
        boolean window = "DELTA".equalsIgnoreCase(exportMode) && fromDate != null && toDate != null;
        int attemptLimit = window ? total : total * 8;
        List<Map<String, Object>> batch = new ArrayList<>();
        int produced = 0;
        int attempts = 0;
        while (produced < total && attempts < attemptLimit) {
            attempts++;
            Map<String, Object> record = buildRecord(versionId, attempts, scope);
            if (!matches(record, scope, exportMode, fromDate, toDate)) {
                continue;
            }
            batch.add(record);
            produced++;
            if (batch.size() >= BATCH_SIZE) {
                consumer.accept(List.copyOf(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            consumer.accept(List.copyOf(batch));
        }
    }

    private Map<String, Object> buildRecord(Long versionId, int index, String scope) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Map<String, Object> record = new LinkedHashMap<>();
        long id = (versionId == null ? 1 : versionId) * 1_000_000L + index;
        record.put("id", id);
        record.put("name", NAMES[index % NAMES.length]);
        record.put("country", COUNTRIES[index % COUNTRIES.length]);
        String recordDate = LocalDate.of(1950 + (index % 50), (index % 12) + 1, (index % 27) + 1).toString();
        record.put("dateOfBirth", recordDate);
        record.put("record_date", recordDate);
        record.put("changedOn", LocalDate.now().minusDays(index % 90).toString());
        record.put("risk_rating", new String[] {"LOW", "MEDIUM", "HIGH"}[index % 3]);
        record.put("status", STATUSES[index % STATUSES.length]);
        record.put("sanctions", random.nextBoolean() || "SANCTIONS".equalsIgnoreCase(scope));
        record.put("worldcheck", random.nextBoolean() || "WORLDCHECK".equalsIgnoreCase(scope));
        record.put("pfa", random.nextBoolean() || "PFA".equalsIgnoreCase(scope));
        record.put("action", ACTIONS[index % ACTIONS.length]);
        record.put("businessLine", new String[] {"CIB", "Retail", "Compliance", "Financial Security"}[index % 4]);
        record.put("region", new String[] {"EMEA", "APAC", "Europe", "Americas", "Global"}[index % 5]);

        if (index % 17 == 0) {
            record.put("country", " ");
        }
        if (index % 29 == 0) {
            record.put("name", "");
        }
        if (index % 31 == 0) {
            record.put("dateOfBirth", null);
            record.put("record_date", null);
        }
        if (index == 20) {
            record.put("name", "");
        }
        if (index == 15) {
            record.put("country", " ");
        }
        if (index == 14 || index == 34) {
            record.put("dateOfBirth", null);
            record.put("record_date", null);
        }
        return record;
    }

    private boolean matches(Map<String, Object> record, String scope, String exportMode, LocalDate fromDate, LocalDate toDate) {
        if ("DELTA".equalsIgnoreCase(exportMode)) {
            if (record.get("action") == null) {
                return false;
            }
            if (fromDate != null && toDate != null) {
                LocalDate changedOn = LocalDate.parse(String.valueOf(record.get("changedOn")));
                if (changedOn.isBefore(fromDate) || changedOn.isAfter(toDate)) {
                    return false;
                }
            }
        }
        if (scope == null || "ALL".equalsIgnoreCase(scope)) {
            return true;
        }
        return switch (scope.toUpperCase()) {
            case "SANCTIONS" -> Boolean.TRUE.equals(record.get("sanctions"));
            case "WORLDCHECK" -> Boolean.TRUE.equals(record.get("worldcheck"));
            case "PFA" -> Boolean.TRUE.equals(record.get("pfa"));
            default -> true;
        };
    }
}
