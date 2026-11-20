package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.format.RecordBatchProvider;
import com.bnpp.export.model.CertifiedVersion;
import com.bnpp.export.model.FormatField;
import com.bnpp.export.repository.CertifiedVersionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a few sample rows for a template that is still being edited, so the user can see
 * what the file will contain before saving it. Nothing is stored.
 */
@Service
public class TemplatePreviewService {

    private static final int SAMPLE_SIZE = 3;

    private final FormatTemplateService formatTemplateService;
    private final CertifiedVersionRepository certifiedVersionRepository;
    private final RecordBatchProvider recordBatchProvider;
    private final GoldenSourceEnricher goldenSourceEnricher;

    public TemplatePreviewService(FormatTemplateService formatTemplateService,
                                  CertifiedVersionRepository certifiedVersionRepository,
                                  RecordBatchProvider recordBatchProvider,
                                  GoldenSourceEnricher goldenSourceEnricher) {
        this.formatTemplateService = formatTemplateService;
        this.certifiedVersionRepository = certifiedVersionRepository;
        this.recordBatchProvider = recordBatchProvider;
        this.goldenSourceEnricher = goldenSourceEnricher;
    }

    public Preview preview(List<FormatFieldRequest> requested) {
        List<FormatField> fields = formatTemplateService.previewFields(requested);
        if (fields.isEmpty()) {
            return new Preview(null, null, List.of(), "Add a column to see the file.");
        }
        CertifiedVersion version = certifiedVersionRepository.findAllByOrderByVersionIdAsc().stream()
                .filter(candidate -> candidate.isOperatorVisible() && "CERTIFIED".equals(candidate.getStatus()))
                .findFirst()
                .orElse(null);
        if (version == null) {
            return new Preview(null, null, List.of(), "No certified list version is available for a sample.");
        }
        List<Map<String, Object>> sample = new ArrayList<>();
        try {
            recordBatchProvider.stream(version.getVersionId(), "ALL", "FULL", null, null, SAMPLE_SIZE, batch -> {
                for (Map<String, Object> record : batch) {
                    sample.add(record);
                    if (sample.size() >= SAMPLE_SIZE) {
                        throw new SampleComplete();
                    }
                }
            });
        } catch (SampleComplete ignored) {
            // enough rows for the sample
        } catch (RuntimeException ex) {
            return new Preview(version.getVersionId(), version.getListName(), List.of(),
                    "Sample records are not available: " + ex.getMessage());
        }
        List<Map<String, Object>> enriched;
        try {
            enriched = goldenSourceEnricher.enrich(sample, fields);
        } catch (RuntimeException ex) {
            return new Preview(version.getVersionId(), version.getListName(), List.of(), ex.getMessage());
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> record : enriched) {
            List<String> row = new ArrayList<>();
            for (FormatField field : fields) {
                Object value = record.get(field.recordKey());
                if (value == null && "record_date".equals(field.getSourceColumn()) && !field.isGoldenSource()) {
                    value = record.get("dateOfBirth");
                }
                row.add(value == null ? null : String.valueOf(value));
            }
            rows.add(row);
        }
        List<String> ids = enriched.stream().map(record -> String.valueOf(record.get("id"))).toList();
        return new Preview(version.getVersionId(), version.getListName(), ids, rows, null);
    }

    public record Preview(Long versionId, String listName, List<String> recordIds, List<List<String>> rows, String message) {
        Preview(Long versionId, String listName, List<List<String>> rows, String message) {
            this(versionId, listName, List.of(), rows, message);
        }
    }

    private static final class SampleComplete extends RuntimeException {
        SampleComplete() {
            super(null, null, false, false);
        }
    }
}
