package com.bnpp.export.service.impl;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.dto.SourceTableResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatFilter;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.exception.ForbiddenException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.format.XmlFormatGenerator;
import com.bnpp.export.repository.FormatFieldRepository;
import com.bnpp.export.repository.FormatFilterRepository;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.RowFilterService;
import com.bnpp.export.service.SourceCatalogService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Implementation of {@link FormatTemplateService}. */
@Service
public class FormatTemplateServiceImpl implements FormatTemplateService {

    private final FormatTemplateRepository templateRepository;
    private final FormatFieldRepository fieldRepository;
    private final FormatFilterRepository filterRepository;
    private final SubscriberRepository subscriberRepository;
    private final SourceCatalogService sourceCatalog;
    private final RowFilterService rowFilters;
    private final AuditService auditService;

    public FormatTemplateServiceImpl(FormatTemplateRepository templateRepository,
                                 FormatFieldRepository fieldRepository,
                                 FormatFilterRepository filterRepository,
                                 SubscriberRepository subscriberRepository,
                                 SourceCatalogService sourceCatalog,
                                 RowFilterService rowFilters,
                                 AuditService auditService) {
        this.templateRepository = templateRepository;
        this.fieldRepository = fieldRepository;
        this.filterRepository = filterRepository;
        this.subscriberRepository = subscriberRepository;
        this.sourceCatalog = sourceCatalog;
        this.rowFilters = rowFilters;
        this.auditService = auditService;
    }

    @Override
    public List<SourceTableResponse> sources() {
        List<SourceTableResponse> sources = new ArrayList<>();
        for (SourceCatalogService.SourceTable table : sourceCatalog.tables()) {
            boolean linkable = table.keyColumn() != null;
            sources.add(new SourceTableResponse(
                    table.name(), table.name(), table.keyColumn(), linkable,
                    table.columns().stream()
                            .map(column -> new SourceTableResponse.Column(column.name(), column.type(), column.kind()))
                            .toList()));
        }
        return sources;
    }

    @Override
    public List<FormatTemplateResponse> list() {
        return templateRepository.findAllByOrderByNameAscVersionNumberDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<FormatField> fields(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return fieldRepository.findByTemplateIdOrderByPositionAsc(templateId);
    }

    @Override
    public java.util.Optional<FormatTemplate> inUse(Long familyId) {
        if (familyId == null) {
            return java.util.Optional.empty();
        }
        return templateRepository.findFirstByFamilyIdAndActiveTrueOrderByVersionNumberDesc(familyId);
    }

    @Transactional
    @Override
    public FormatTemplateResponse save(FormatTemplateRequest request) {
        String name = request.name().trim();
        List<FormatFieldRequest> fields = validateFields(request.fields(), true);
        List<FormatFilter> filters = rowFilters.validate(request.filters(), toFields(fields));
        FormatTemplate latest = templateRepository
                .findFirstByNameIgnoreCaseOrderByVersionNumberDesc(name).orElse(null);
        FormatTemplate template = new FormatTemplate();
        template.setName(latest == null ? name : latest.getName());
        template.setFamilyId(latest == null ? null : latest.getFamilyId());
        template.setVersionNumber(latest == null ? 1 : latest.getVersionNumber() + 1);
        template.setActive(false);
        template.setStatus("PENDING");
        template.setCreatedBy(CurrentUser.username());
        template.setCreatedByName(CurrentUser.displayName());
        template = templateRepository.save(template);
        if (template.getFamilyId() == null) {
            template.setFamilyId(template.getId());
            template = templateRepository.save(template);
        }
        boolean resubmission = latest != null && "CHANGES_REQUESTED".equals(latest.getStatus());
        if (latest != null && "PENDING".equals(latest.getStatus())) {
            latest.setStatus("SUPERSEDED");
            latest.setReviewedBy(CurrentUser.username());
            latest.setReviewedByName(CurrentUser.displayName());
            latest.setReviewedAt(LocalDateTime.now());
            templateRepository.save(latest);
            auditService.template(latest.getId(), "TEMPLATE_SUPERSEDED",
                    "v" + latest.getVersionNumber() + " replaced by v" + template.getVersionNumber() + " before it was reviewed");
        }
        saveFields(template.getId(), fields);
        for (FormatFilter filter : filters) {
            filter.setTemplateId(template.getId());
        }
        filterRepository.saveAll(filters);
        auditService.template(template.getId(), resubmission ? "TEMPLATE_RESUBMITTED" : "TEMPLATE_SUBMITTED",
                "\"" + template.getName() + "\" v" + template.getVersionNumber() + ": " + describe(fields)
                        + ". Rows: " + describeRows(filters));
        return toResponse(template);
    }

    /** The row filters in words, or "every list entry" when there are none. */
    private String describeRows(List<FormatFilter> filters) {
        return filters.isEmpty() ? "every list entry"
                : String.join("; ", filters.stream().map(rowFilters::describe).toList());
    }

    @Override
    public List<FormatFilter> filters(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return filterRepository.findByTemplateIdOrderByPositionAsc(templateId);
    }

    @Override
    public List<String> filterSummaries(Long templateId) {
        return filters(templateId).stream().map(rowFilters::describe).toList();
    }

    private static List<FormatField> toFields(List<FormatFieldRequest> fields) {
        List<FormatField> rows = new ArrayList<>();
        for (FormatFieldRequest field : fields) {
            FormatField row = new FormatField();
            row.setSourceTable(field.sourceTable());
            row.setSourceColumn(field.sourceColumn());
            row.setHeaderName(field.headerName());
            rows.add(row);
        }
        return rows;
    }

    private String describe(List<FormatFieldRequest> fields) {
        return String.join(", ", fields.stream()
                .map(field -> field.sourceTable() + "." + field.sourceColumn()
                        + (field.sourceColumn().equals(field.headerName()) ? "" : " as " + field.headerName()))
                .toList());
    }

    @Override
    public String checkChoice(Long familyId) {
        if (familyId == null) {
            throw new BadRequestException("Choose a list for this subscriber");
        }
        FormatTemplate latest = templateRepository.findFirstByFamilyIdOrderByVersionNumberDesc(familyId)
                .orElseThrow(() -> new BadRequestException("Unknown list"));
        if (inUse(familyId).isEmpty()) {
            throw new BadRequestException("\"" + latest.getName()
                    + "\" has no approved version yet. It can be chosen once a List Owner approves it.");
        }
        return latest.getName();
    }

    @Override
    public String templateName(Long familyId) {
        return familyId == null ? null : templateRepository.findFirstByFamilyIdOrderByVersionNumberDesc(familyId)
                .map(FormatTemplate::getName).orElse(null);
    }

    @Transactional
    @Override
    public FormatTemplateResponse approve(Long id, String comment) {
        FormatTemplate template = forDecision(id);
        templateRepository.findByFamilyIdAndActiveTrue(template.getFamilyId()).forEach(existing -> {
            existing.setActive(false);
            templateRepository.save(existing);
        });
        template.setStatus("APPROVED");
        template.setActive(true);
        template.setApprovedBy(CurrentUser.username());
        template.setApprovedByName(CurrentUser.displayName());
        template.setApprovedAt(LocalDateTime.now());
        decided(template, StringUtils.trimToNull(comment));
        auditService.template(template.getId(), "TEMPLATE_APPROVED",
                "\"" + template.getName() + "\" v" + template.getVersionNumber() + " is now in use"
                        + (template.getReviewComment() == null ? "" : ". Comment: " + template.getReviewComment()));
        return toResponse(template);
    }

    @Override
    public FormatTemplateResponse approve(Long id) {
        return approve(id, null);
    }

    @Transactional
    @Override
    public FormatTemplateResponse reject(Long id, String comment) {
        FormatTemplate template = forDecision(id);
        template.setStatus("REJECTED");
        decided(template, requiredComment(comment, "rejecting"));
        auditService.template(template.getId(), "TEMPLATE_REJECTED",
                "v" + template.getVersionNumber() + " rejected. Comment: " + template.getReviewComment());
        return toResponse(template);
    }

    @Transactional
    @Override
    public FormatTemplateResponse requestChanges(Long id, String comment) {
        FormatTemplate template = forDecision(id);
        template.setStatus("CHANGES_REQUESTED");
        decided(template, requiredComment(comment, "requesting changes"));
        auditService.template(template.getId(), "TEMPLATE_CHANGES_REQUESTED",
                "v" + template.getVersionNumber() + " returned to " + template.getCreatedByName() + ". Comment: " + template.getReviewComment());
        return toResponse(template);
    }

    /** A version an approver may decide on now: waiting for review, and not submitted by them. */
    private FormatTemplate forDecision(Long id) {
        if (!CurrentUser.approver()) {
            throw new ForbiddenException("Only a List Owner can review a list");
        }
        FormatTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("List not found"));
        if ("SUPERSEDED".equals(template.getStatus())) {
            int newest = templateRepository.findFirstByFamilyIdOrderByVersionNumberDesc(template.getFamilyId())
                    .map(FormatTemplate::getVersionNumber).orElse(template.getVersionNumber());
            throw new ConflictException("Version " + template.getVersionNumber()
                    + " was replaced by version " + newest + " before it was reviewed. Review the newest version.");
        }
        if (!"PENDING".equals(template.getStatus())) {
            throw new ConflictException("This version has already been reviewed ("
                    + template.getStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
        }
        if (template.getCreatedBy() != null && template.getCreatedBy().equalsIgnoreCase(CurrentUser.username())) {
            throw new ForbiddenException("You submitted this version. Another List Owner must review it.");
        }
        return template;
    }

    private void decided(FormatTemplate template, String comment) {
        template.setReviewedBy(CurrentUser.username());
        template.setReviewedByName(CurrentUser.displayName());
        template.setReviewedAt(LocalDateTime.now());
        template.setReviewComment(comment);
        templateRepository.save(template);
    }

    private static String requiredComment(String comment, String action) {
        String text = StringUtils.trimToEmpty(comment);
        if (text.length() < 10) {
            throw new BadRequestException("Explain to the List Manager why you are " + action + " (at least 10 characters)");
        }
        if (text.length() > 2000) {
            throw new BadRequestException("Keep the comment under 2,000 characters");
        }
        return text;
    }

    @Transactional
    @Override
    public void seedDemoTemplates() {
        SourceCatalogService.SourceTable party = sourceCatalog.table("GS_PARTY").orElse(null);
        if (party == null || party.keyColumn() == null) {
            return;
        }
        String key = party.keyColumn();
        java.util.function.Function<String, String> column = name -> party.column(name).map(SourceCatalogService.SourceColumn::name).orElse(null);
        if (column.apply("FULL_NAME") == null || column.apply("NATIONALITY") == null || column.apply("PARTY_TYPE") == null) {
            return;
        }
        String table = party.name();
        Long csv = seedIfMissing("Party Basic", "CSV Export", List.of(
                new FormatFieldRequest(table, key, "bnp_id", key, SourceCatalogService.RECORD_KEY),
                new FormatFieldRequest(table, column.apply("FULL_NAME"), "name", key, SourceCatalogService.RECORD_KEY),
                new FormatFieldRequest(table, column.apply("NATIONALITY"), "nationality", key, SourceCatalogService.RECORD_KEY)));
        Long xml = seedIfMissing("Party Detail", "XML Export", List.of(
                new FormatFieldRequest(table, key, "BnpId", key, SourceCatalogService.RECORD_KEY),
                new FormatFieldRequest(table, column.apply("FULL_NAME"), "Name", key, SourceCatalogService.RECORD_KEY),
                new FormatFieldRequest(table, column.apply("NATIONALITY"), "Nationality", key, SourceCatalogService.RECORD_KEY),
                new FormatFieldRequest(table, column.apply("PARTY_TYPE"), "PartyType", key, SourceCatalogService.RECORD_KEY)));
        for (SubscriberProfile subscriber : subscriberRepository.findAll()) {
            if (subscriber.getTemplateFamilyId() == null) {
                subscriber.setTemplateFamilyId("CSV".equals(subscriber.getFormatType()) ? csv : xml);
                subscriberRepository.save(subscriber);
            }
        }
    }

    /** Returns the template (family id) with this name, creating an approved version 1 if it is missing. */
    private Long seedIfMissing(String name, String formerName, List<FormatFieldRequest> defaultFields) {
        FormatTemplate existing = templateRepository
                .findFirstByNameIgnoreCaseOrderByVersionNumberDesc(name).orElse(null);
        FormatTemplate former = existing != null ? null
                : templateRepository.findFirstByNameIgnoreCaseOrderByVersionNumberDesc(formerName).orElse(null);
        if (former != null) {
            // The demo lists were named after a file format before lists had none; give them their new name.
            for (FormatTemplate version : templateRepository.findAll()) {
                if (former.getFamilyId().equals(version.getFamilyId())) {
                    version.setName(name);
                    templateRepository.save(version);
                }
            }
            return former.getFamilyId();
        }
        if (existing != null) {
            return existing.getFamilyId();
        }
        FormatTemplate template = new FormatTemplate();
        template.setName(name);
        template.setVersionNumber(1);
        template.setActive(true);
        template.setStatus("APPROVED");
        template.setCreatedBy("system");
        template.setCreatedByName("System");
        template = templateRepository.save(template);
        template.setFamilyId(template.getId());
        templateRepository.save(template);
        saveFields(template.getId(), defaultFields);
        return template.getFamilyId();
    }

    private void saveFields(Long templateId, List<FormatFieldRequest> fields) {
        int position = 1;
        List<FormatField> rows = new ArrayList<>();
        for (FormatFieldRequest field : fields) {
            FormatField row = new FormatField();
            row.setTemplateId(templateId);
            row.setSourceTable(StringUtils.trimToNull(field.sourceTable()));
            row.setSourceColumn(field.sourceColumn().trim());
            row.setJoinColumn(field.joinColumn());
            row.setJoinField(field.joinField());
            row.setHeaderName(field.headerName().trim());
            row.setPosition(position++);
            rows.add(row);
        }
        fieldRepository.saveAll(rows);
    }

    @Override
    public List<FormatField> previewFields(List<FormatFieldRequest> fields) {
        List<FormatField> rows = new ArrayList<>();
        int position = 1;
        for (FormatFieldRequest field : validateFields(fields, false)) {
            FormatField row = new FormatField();
            row.setSourceTable(field.sourceTable());
            row.setSourceColumn(field.sourceColumn());
            row.setHeaderName(field.headerName());
            row.setJoinColumn(field.joinColumn());
            row.setJoinField(field.joinField());
            row.setPosition(position++);
            rows.add(row);
        }
        return rows;
    }

    private List<FormatFieldRequest> validateFields(List<FormatFieldRequest> fields, boolean strictHeaders) {
        if (fields == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> headers = new LinkedHashSet<>();
        Set<String> elements = new LinkedHashSet<>();
        Map<String, String> linkByTable = new java.util.HashMap<>();
        List<SourceCatalogService.SourceTable> tables = null;
        List<FormatFieldRequest> checked = new ArrayList<>();
        for (FormatFieldRequest field : fields) {
            String table = StringUtils.trimToNull(field.sourceTable());
            String source = field.sourceColumn() == null ? "" : field.sourceColumn().trim();
            String joinColumn = null;
            String joinField = null;
            if (table == null) {
                // A file holds only the gs_ columns picked on the template. List entries decide which
                // rows there are; their own fields are not offered as columns.
                throw new BadRequestException("Pick columns from gs_ tables. \"" + source + "\" is not a gs_ column.");
            } else {
                if (tables == null) {
                    tables = sourceCatalog.tables();
                }
                String wanted = table;
                SourceCatalogService.SourceTable found = tables.stream()
                        .filter(candidate -> candidate.name().equalsIgnoreCase(wanted))
                        .findFirst()
                        .orElseThrow(() -> new BadRequestException("Unknown source table " + wanted + ". Only gs_ tables can be used."));
                String wantedColumn = source;
                SourceCatalogService.SourceColumn column = found.column(wantedColumn)
                        .orElseThrow(() -> new BadRequestException("Unknown column " + wantedColumn + " on " + found.name()));
                table = found.name();
                source = column.name();
                Link link = resolveLink(found, field.joinColumn(), field.joinField());
                joinColumn = link.column();
                joinField = link.field();
                String previous = linkByTable.putIfAbsent(table.toLowerCase(Locale.ROOT), joinColumn + "=" + joinField);
                if (previous != null && !previous.equals(joinColumn + "=" + joinField)) {
                    throw new BadRequestException("Columns from " + table + " must all use the same link to the record");
                }
            }
            String header = field.headerName() == null ? "" : field.headerName().trim();
            if (!strictHeaders) {
                checked.add(new FormatFieldRequest(table, source, header, joinColumn, joinField));
                continue;
            }
            if (header.isEmpty()) {
                throw new BadRequestException("Header name is required");
            }
            String label = table == null ? source : table + "." + source;
            if (!seen.add(label.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Column " + label + " is already on the list");
            }
            if (!headers.add(header.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Header " + header + " is used twice. Give each export column its own name.");
            }
            // The list can be sent in any format: in XML the name becomes an element, which must be unique too.
            String element = XmlFormatGenerator.elementName(header);
            if (!elements.add(element.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Columns \"" + header + "\" and another one would both be <" + element
                        + "> in an XML file. Give them names that differ by more than spaces or symbols.");
            }
            checked.add(new FormatFieldRequest(table, source, header, joinColumn, joinField));
        }
        return checked;
    }

    @Override
    public Link resolveLink(SourceCatalogService.SourceTable table, String joinColumn, String joinField) {
        if (table.keyColumn() == null) {
            throw new BadRequestException(table.name() + " has no " + SourceCatalogService.KEY + " column, so its rows cannot be matched to list entries");
        }
        return new Link(table.keyColumn(), SourceCatalogService.RECORD_KEY);
    }

    private List<String> usedBy(Long familyId) {
        if (familyId == null) {
            return List.of();
        }
        return subscriberRepository.findByTemplateFamilyIdOrderByNameAsc(familyId).stream()
                .map(subscriber -> subscriber.getName())
                .toList();
    }

    private FormatTemplateResponse toResponse(FormatTemplate template) {
        List<FormatTemplateResponse.FieldResponse> fields = fieldRepository.findByTemplateIdOrderByPositionAsc(template.getId()).stream()
                .map(field -> new FormatTemplateResponse.FieldResponse(
                        field.getId(),
                        field.getSourceTable(),
                        field.getSourceColumn(),
                        field.getHeaderName(),
                        "Matched where " + StringUtils.defaultString(field.getJoinColumn(), SourceCatalogService.KEY)
                                + " = list entry " + StringUtils.defaultString(field.getJoinField(), SourceCatalogService.RECORD_KEY),
                        field.getPosition(),
                        field.getJoinColumn(),
                        field.getJoinField()))
                .toList();
        return new FormatTemplateResponse(
                template.getId(),
                template.getFamilyId(),
                template.getName(),
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
                filters(template.getId()).stream()
                        .map(filter -> new FormatTemplateResponse.FilterResponse(
                                filter.getSourceTable(),
                                filter.getSourceColumn(),
                                filter.getOperator(),
                                rowFilters.values(filter),
                                filter.isIncludeBlank(),
                                rowFilters.describe(filter)))
                        .toList(),
                usedBy(template.getFamilyId()),
                template.getReviewedBy(),
                template.getReviewedByName(),
                template.getReviewedAt(),
                template.getReviewComment());
    }
}
