package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.format.XmlFormatGenerator;
import com.bnpp.export.model.FormatField;
import com.bnpp.export.model.FormatTemplate;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.FormatFieldRepository;
import com.bnpp.export.repository.FormatFilterRepository;
import com.bnpp.export.model.FormatFilter;
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

    /** The only file formats the module builds. */
    public static final List<String> FORMATS = List.of("CSV", XmlFormatGenerator.FORMAT);

    private final FormatTemplateRepository templateRepository;
    private final FormatFieldRepository fieldRepository;
    private final FormatFilterRepository filterRepository;
    private final SubscriberRepository subscriberRepository;
    private final SourceCatalogService sourceCatalog;
    private final RowFilterService rowFilters;
    private final AuditService auditService;

    public FormatTemplateService(FormatTemplateRepository templateRepository,
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

    /** Every gs_ table in the source database, with its columns. */
    public List<SourceTableResponse> sources() {
        List<SourceTableResponse> sources = new ArrayList<>();
        for (SourceCatalogService.SourceTable table : sourceCatalog.tables()) {
            boolean linkable = table.keyColumn() != null;
            sources.add(new SourceTableResponse(
                    table.name(), table.name(), table.keyColumn(), linkable,
                    table.columns().stream()
                            .map(column -> new SourceColumnResponse(column.name(), column.type(), column.kind()))
                            .toList()));
        }
        return sources;
    }

    /**
     * @param keyColumn the table's BNP_ID column, or null when it has none (the table cannot be used)
     * @param joinable  whether the table can be matched to list entries
     */
    public record SourceTableResponse(String table, String label, String keyColumn,
                                      boolean joinable, List<SourceColumnResponse> columns) {
    }

    /** @param kind text, number, date or other: how the column can be filtered */
    public record SourceColumnResponse(String name, String type, String kind) {
    }

    public List<FormatTemplateResponse> list() {
        return templateRepository.findAllByOrderByFormatTypeAscNameAscVersionNumberDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    public List<FormatField> fields(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return fieldRepository.findByTemplateIdOrderByPositionAsc(templateId);
    }

    /** The approved version in use of a template (family), if it has one. */
    public java.util.Optional<FormatTemplate> inUse(Long familyId) {
        if (familyId == null) {
            return java.util.Optional.empty();
        }
        return templateRepository.findFirstByFamilyIdAndActiveTrueOrderByVersionNumberDesc(familyId);
    }

    /**
     * Saves a new version for approval. A name that already exists for this format adds a version to
     * that template; a new name starts a new template at version 1. Nothing changes for subscribers
     * until an approver approves the version.
     *
     * A version still waiting for review is replaced (SUPERSEDED), so approvers only ever review the
     * newest submission. Saving after an approver requested changes is a resubmission.
     */
    @Transactional
    public FormatTemplateResponse save(FormatTemplateRequest request) {
        String formatType = normalizeFormat(request.formatType());
        String name = request.name().trim();
        List<FormatFieldRequest> fields = validateFields(request.fields(), true, formatType);
        List<FormatFilter> filters = rowFilters.validate(request.filters(), toFields(fields));
        FormatTemplate latest = templateRepository
                .findFirstByFormatTypeAndNameIgnoreCaseOrderByVersionNumberDesc(formatType, name).orElse(null);
        FormatTemplate template = new FormatTemplate();
        template.setName(latest == null ? name : latest.getName());
        template.setFormatType(formatType);
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
                formatType + " \"" + template.getName() + "\" v" + template.getVersionNumber() + ": " + describe(fields)
                        + ". Rows: " + describeRows(filters));
        return toResponse(template);
    }

    /** The row filters in words, or "every list entry" when there are none. */
    private String describeRows(List<FormatFilter> filters) {
        return filters.isEmpty() ? "every list entry"
                : String.join("; ", filters.stream().map(rowFilters::describe).toList());
    }

    /** The row filters of a template version, in order. */
    public List<FormatFilter> filters(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return filterRepository.findByTemplateIdOrderByPositionAsc(templateId);
    }

    /** The row filters of a template version in words, for screens and messages. */
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

    /**
     * Checks a template chosen for a subscriber: it must exist, be of the subscriber's format and have
     * an approved version. Returns the template's name for messages and the audit log.
     */
    public String checkChoice(Long familyId, String formatType) {
        if (familyId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Choose a template for this subscriber");
        }
        FormatTemplate latest = templateRepository.findFirstByFamilyIdOrderByVersionNumberDesc(familyId)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Unknown template"));
        if (!latest.getFormatType().equalsIgnoreCase(formatType)) {
            throw new ResponseStatusException(BAD_REQUEST, "\"" + latest.getName() + "\" is a " + latest.getFormatType()
                    + " template; this subscriber receives " + formatType + " files");
        }
        if (inUse(familyId).isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "\"" + latest.getName()
                    + "\" has no approved version yet. It can be chosen once an approver approves it.");
        }
        return latest.getName();
    }

    /** The name of a template, for display; null when there is none. */
    public String templateName(Long familyId) {
        return familyId == null ? null : templateRepository.findFirstByFamilyIdOrderByVersionNumberDesc(familyId)
                .map(FormatTemplate::getName).orElse(null);
    }

    /**
     * Puts a version in use for its own template only; other templates are not affected. Its
     * subscribers get it from their next export. The comment is optional.
     */
    @Transactional
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
                template.getFormatType() + " \"" + template.getName() + "\" v" + template.getVersionNumber() + " is now in use"
                        + (template.getReviewComment() == null ? "" : ". Comment: " + template.getReviewComment()));
        return toResponse(template);
    }

    public FormatTemplateResponse approve(Long id) {
        return approve(id, null);
    }

    /** Closes a version for good. The version in use, if any, stays in use. A reason is required. */
    @Transactional
    public FormatTemplateResponse reject(Long id, String comment) {
        FormatTemplate template = forDecision(id);
        template.setStatus("REJECTED");
        decided(template, requiredComment(comment, "rejecting"));
        auditService.template(template.getId(), "TEMPLATE_REJECTED",
                "v" + template.getVersionNumber() + " rejected. Comment: " + template.getReviewComment());
        return toResponse(template);
    }

    /**
     * Returns a version to its maker, who edits it and resubmits it as a new version. The version
     * in use, if any, stays in use meanwhile. What to change is required.
     */
    @Transactional
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
            throw new ResponseStatusException(FORBIDDEN, "Only an approver can review a template");
        }
        FormatTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Template not found"));
        if ("SUPERSEDED".equals(template.getStatus())) {
            int newest = templateRepository.findFirstByFamilyIdOrderByVersionNumberDesc(template.getFamilyId())
                    .map(FormatTemplate::getVersionNumber).orElse(template.getVersionNumber());
            throw new ResponseStatusException(CONFLICT, "Version " + template.getVersionNumber()
                    + " was replaced by version " + newest + " before it was reviewed. Review the newest version.");
        }
        if (!"PENDING".equals(template.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "This version has already been reviewed ("
                    + template.getStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
        }
        if (template.getCreatedBy() != null && template.getCreatedBy().equalsIgnoreCase(CurrentUser.username())) {
            throw new ResponseStatusException(FORBIDDEN, "You submitted this version. Another approver must review it.");
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
            throw new ResponseStatusException(BAD_REQUEST, "Explain to the maker why you are " + action + " (at least 10 characters)");
        }
        if (text.length() > 2000) {
            throw new ResponseStatusException(BAD_REQUEST, "Keep the comment under 2,000 characters");
        }
        return text;
    }

    /**
     * Demo databases only: starts CSV and XML off with an approved template built from GS_PARTY, and
     * gives every demo subscriber without a template the one for its format, so exports work out of
     * the box. Real databases get no templates; users build them from their gs_ tables.
     */
    @Transactional
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
        Long csv = seedIfMissing("CSV", "CSV Export", List.of(
                new FormatFieldRequest(table, key, "bnp_id", key, SourceCatalogService.RECORD_KEY),
                new FormatFieldRequest(table, column.apply("FULL_NAME"), "name", key, SourceCatalogService.RECORD_KEY),
                new FormatFieldRequest(table, column.apply("NATIONALITY"), "nationality", key, SourceCatalogService.RECORD_KEY)));
        Long xml = seedIfMissing(XmlFormatGenerator.FORMAT, "XML Export", List.of(
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
    private Long seedIfMissing(String formatType, String name, List<FormatFieldRequest> defaultFields) {
        FormatTemplate existing = templateRepository
                .findFirstByFormatTypeAndNameIgnoreCaseOrderByVersionNumberDesc(formatType, name).orElse(null);
        if (existing != null) {
            return existing.getFamilyId();
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

    /**
     * Checks the source of each column but not the export names, which may be blank or repeated
     * while the user is still typing. Used by the data explorer.
     */
    public List<FormatField> previewFields(List<FormatFieldRequest> fields) {
        List<FormatField> rows = new ArrayList<>();
        int position = 1;
        for (FormatFieldRequest field : validateFields(fields, false, null)) {
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

    private List<FormatFieldRequest> validateFields(List<FormatFieldRequest> fields, boolean strictHeaders, String formatType) {
        if (fields == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> headers = new LinkedHashSet<>();
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
                throw new ResponseStatusException(BAD_REQUEST,
                        "Pick columns from gs_ tables. \"" + source + "\" is not a gs_ column.");
            } else {
                if (tables == null) {
                    tables = sourceCatalog.tables();
                }
                String wanted = table;
                SourceCatalogService.SourceTable found = tables.stream()
                        .filter(candidate -> candidate.name().equalsIgnoreCase(wanted))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                                "Unknown source table " + wanted + ". Only gs_ tables can be used."));
                String wantedColumn = source;
                SourceCatalogService.SourceColumn column = found.column(wantedColumn)
                        .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                                "Unknown column " + wantedColumn + " on " + found.name()));
                table = found.name();
                source = column.name();
                Link link = resolveLink(found, field.joinColumn(), field.joinField());
                joinColumn = link.column();
                joinField = link.field();
                String previous = linkByTable.putIfAbsent(table.toLowerCase(Locale.ROOT), joinColumn + "=" + joinField);
                if (previous != null && !previous.equals(joinColumn + "=" + joinField)) {
                    throw new ResponseStatusException(BAD_REQUEST,
                            "Columns from " + table + " must all use the same link to the record");
                }
            }
            String header = field.headerName() == null ? "" : field.headerName().trim();
            if (!strictHeaders) {
                checked.add(new FormatFieldRequest(table, source, header, joinColumn, joinField));
                continue;
            }
            if (header.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST, "Header name is required");
            }
            if (XmlFormatGenerator.FORMAT.equals(formatType)) {
                String problem = XmlFormatGenerator.elementNameProblem(header);
                if (problem != null) {
                    throw new ResponseStatusException(BAD_REQUEST, problem);
                }
            }
            String label = table == null ? source : table + "." + source;
            if (!seen.add(label.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(BAD_REQUEST, "Column " + label + " is already on the template");
            }
            if (!headers.add(header.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(BAD_REQUEST, "Header " + header + " is used twice. Give each export column its own name.");
            }
            checked.add(new FormatFieldRequest(table, source, header, joinColumn, joinField));
        }
        return checked;
    }

    /** How a gs_ table's rows are matched to a list record: table column = record field. */
    public record Link(String column, String field) {
    }

    /**
     * Every gs_ table is matched to a list entry on BNP_ID, the key all gs_ tables share. A table
     * without a BNP_ID column cannot be used. (Link values sent by a client are ignored.)
     */
    public Link resolveLink(SourceCatalogService.SourceTable table, String joinColumn, String joinField) {
        if (table.keyColumn() == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    table.name() + " has no " + SourceCatalogService.KEY + " column, so its rows cannot be matched to list entries");
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

    private String normalizeFormat(String formatType) {
        String normalized = formatType == null ? "" : formatType.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (normalized.endsWith("_EXPORT")) {
            normalized = normalized.substring(0, normalized.length() - "_EXPORT".length());
        }
        if (!FORMATS.contains(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Format must be CSV or XML");
        }
        return normalized;
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
