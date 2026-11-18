package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.model.FormatField;
import com.bnpp.export.model.FormatTemplate;
import com.bnpp.export.repository.FormatFieldRepository;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class FormatTemplateService {

    public static final String SOURCE_TABLE = "List record";

    public static final List<String> SOURCE_COLUMNS = List.of(
            "id", "action", "record_date", "name", "country", "risk_rating");

    private static final Map<String, String> COLUMN_DETAILS = Map.of(
            "id", "Record identifier",
            "action", "Add, modify, or delete",
            "record_date", "Date of the list record",
            "name", "Party name",
            "country", "Country of the record",
            "risk_rating", "Risk rating on the record");

    private final FormatTemplateRepository templateRepository;
    private final FormatFieldRepository fieldRepository;
    private final SubscriberRepository subscriberRepository;

    public FormatTemplateService(FormatTemplateRepository templateRepository,
                                 FormatFieldRepository fieldRepository,
                                 SubscriberRepository subscriberRepository) {
        this.templateRepository = templateRepository;
        this.fieldRepository = fieldRepository;
        this.subscriberRepository = subscriberRepository;
    }

    public List<FormatTemplateResponse> list() {
        return templateRepository.findAllByOrderByFormatTypeAscVersionNumberDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    public List<FormatField> fields(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return fieldRepository.findByTemplateIdOrderByPositionAsc(templateId);
    }

    public List<FormatField> activeFields(String formatType) {
        return activeTemplate(formatType)
                .map(template -> fieldRepository.findByTemplateIdOrderByPositionAsc(template.getId()))
                .orElse(List.of());
    }

    public java.util.Optional<FormatTemplate> activeTemplate(String formatType) {
        return templateRepository.findFirstByFormatTypeAndActiveTrueOrderByVersionNumberDesc(formatType.toUpperCase(Locale.ROOT));
    }

    @Transactional
    public FormatTemplateResponse save(FormatTemplateRequest request) {
        String formatType = normalizeFormat(request.formatType());
        List<FormatFieldRequest> fields = validateFields(request.fields());
        int nextVersion = templateRepository.findFirstByFormatTypeOrderByVersionNumberDesc(formatType)
                .map(existing -> existing.getVersionNumber() + 1)
                .orElse(1);
        FormatTemplate template = new FormatTemplate();
        template.setName(request.name().trim());
        template.setFormatType(formatType);
        template.setVersionNumber(nextVersion);
        template.setActive(false);
        template.setStatus("PENDING");
        template.setCreatedBy(CurrentUser.username());
        template.setCreatedByName(CurrentUser.displayName());
        template = templateRepository.save(template);
        saveFields(template.getId(), fields);
        return toResponse(template);
    }

    @Transactional
    public FormatTemplateResponse approve(Long id) {
        if (!CurrentUser.approver()) {
            throw new ResponseStatusException(FORBIDDEN, "Only an approver can approve a template");
        }
        FormatTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Template not found"));
        if (!"PENDING".equals(template.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "This template is not waiting for approval");
        }
        templateRepository.findByFormatTypeAndActiveTrue(template.getFormatType()).forEach(existing -> {
            existing.setActive(false);
            templateRepository.save(existing);
        });
        template.setStatus("APPROVED");
        template.setActive(true);
        template.setApprovedBy(CurrentUser.username());
        template.setApprovedByName(CurrentUser.displayName());
        template.setApprovedAt(LocalDateTime.now());
        templateRepository.save(template);
        return toResponse(template);
    }

    @Transactional
    public void seedDefaultCsv() {
        seedFormatIfMissing("CSV", "CSV Export", List.of(
                new FormatFieldRequest("id", "id"),
                new FormatFieldRequest("action", "action"),
                new FormatFieldRequest("record_date", "record_date")));
        seedFormatIfMissing("GENERIC_XML", "Generic XML Export", List.of(
                new FormatFieldRequest("id", "id"),
                new FormatFieldRequest("name", "name"),
                new FormatFieldRequest("country", "country"),
                new FormatFieldRequest("record_date", "record_date"),
                new FormatFieldRequest("action", "action")));
        seedFormatIfMissing("STANDARD_XML", "Standard XML Export", List.of(
                new FormatFieldRequest("id", "Id"),
                new FormatFieldRequest("name", "Name"),
                new FormatFieldRequest("country", "Country"),
                new FormatFieldRequest("record_date", "RecordDate"),
                new FormatFieldRequest("action", "Action")));
    }

    private void seedFormatIfMissing(String formatType, String name, List<FormatFieldRequest> defaultFields) {
        if (templateRepository.findFirstByFormatTypeOrderByVersionNumberDesc(formatType).isPresent()) {
            return;
        }
        FormatTemplate template = new FormatTemplate();
        template.setName(name);
        template.setFormatType(formatType);
        template.setVersionNumber(1);
        template.setActive(true);
        template.setStatus("APPROVED");
        template.setCreatedBy("system");
        template.setCreatedByName("System");
        template = templateRepository.save(template);
        saveFields(template.getId(), defaultFields);
    }

    private void saveFields(Long templateId, List<FormatFieldRequest> fields) {
        int position = 1;
        List<FormatField> rows = new ArrayList<>();
        for (FormatFieldRequest field : fields) {
            FormatField row = new FormatField();
            row.setTemplateId(templateId);
            row.setSourceColumn(field.sourceColumn().trim());
            row.setHeaderName(field.headerName().trim());
            row.setPosition(position++);
            rows.add(row);
        }
        fieldRepository.saveAll(rows);
    }

    private List<FormatFieldRequest> validateFields(List<FormatFieldRequest> fields) {
        Set<String> seen = new LinkedHashSet<>();
        for (FormatFieldRequest field : fields) {
            String source = field.sourceColumn() == null ? "" : field.sourceColumn().trim();
            if (!SOURCE_COLUMNS.contains(source)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown source column " + source);
            }
            if (StringUtils.isBlank(field.headerName())) {
                throw new ResponseStatusException(BAD_REQUEST, "Header name is required");
            }
            if (!seen.add(source)) {
                throw new ResponseStatusException(BAD_REQUEST, "Column " + source + " is already on the template");
            }
        }
        return fields;
    }

    private List<String> usedBy(String formatType) {
        return subscriberRepository.findByFormatTypeIgnoreCaseAndActiveTrueOrderByNameAsc(formatType).stream()
                .map(subscriber -> subscriber.getName())
                .toList();
    }

    private String normalizeFormat(String formatType) {
        String normalized = formatType == null ? "" : formatType.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if ("CSV_EXPORT".equals(normalized)) {
            normalized = "CSV";
        } else if ("GENERIC_XML_EXPORT".equals(normalized)) {
            normalized = "GENERIC_XML";
        } else if ("STANDARD_XML_EXPORT".equals(normalized)) {
            normalized = "STANDARD_XML";
        }
        if (!Set.of("CSV", "GENERIC_XML", "STANDARD_XML").contains(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Only CSV, Generic XML, and Standard XML templates change the file. Fircosoft layout stays fixed.");
        }
        return normalized;
    }

    private FormatTemplateResponse toResponse(FormatTemplate template) {
        List<FormatTemplateResponse.FieldResponse> fields = fieldRepository.findByTemplateIdOrderByPositionAsc(template.getId()).stream()
                .map(field -> new FormatTemplateResponse.FieldResponse(
                        field.getId(),
                        SOURCE_TABLE,
                        field.getSourceColumn(),
                        field.getHeaderName(),
                        COLUMN_DETAILS.getOrDefault(field.getSourceColumn(), field.getSourceColumn()),
                        field.getPosition()))
                .toList();
        return new FormatTemplateResponse(
                template.getId(),
                template.getName(),
                template.getFormatType(),
                template.getVersionNumber(),
                template.isActive(),
                template.getStatus(),
                template.getCreatedBy(),
                template.getCreatedByName(),
                template.getApprovedBy(),
                template.getApprovedByName(),
                template.getApprovedAt(),
                template.getCreatedAt(),
                fields,
                usedBy(template.getFormatType()));
    }
}
