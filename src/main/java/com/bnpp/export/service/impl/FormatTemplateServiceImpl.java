package com.bnpp.export.service.impl;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.exception.ForbiddenException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.model.ResolvedGroup;
import com.bnpp.export.model.SourceTable;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.repository.FormatFieldRepository;
import com.bnpp.export.repository.FormatTemplateRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.TableGroupService;
import com.bnpp.export.util.XmlNames;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Implementation of {@link FormatTemplateService}. */
@Service
public class FormatTemplateServiceImpl implements FormatTemplateService {

    private final FormatTemplateRepository templateRepository;
    private final FormatFieldRepository fieldRepository;
    private final SubscriberRepository subscriberRepository;
    private final TableGroupService tableGroups;
    private final AuditService auditService;

    public FormatTemplateServiceImpl(FormatTemplateRepository templateRepository, FormatFieldRepository fieldRepository,
                                     SubscriberRepository subscriberRepository, TableGroupService tableGroups, AuditService auditService) {
        this.templateRepository = templateRepository;
        this.fieldRepository = fieldRepository;
        this.subscriberRepository = subscriberRepository;
        this.tableGroups = tableGroups;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ reading

    @Override
    public List<FormatTemplateResponse> list() {
        // Every version with its columns and subscribers in three queries, however many versions there are
        // (the List and Approvals pages poll this).
        Map<Long, List<FormatField>> fields = fieldRepository.findAllByOrderByTemplateIdAscPositionAsc().stream()
                .collect(Collectors.groupingBy(FormatField::getTemplateId));
        Map<Long, List<String>> usedBy = subscriberRepository.findByTemplateFamilyIdIsNotNullOrderByNameAsc().stream()
                .collect(Collectors.groupingBy(SubscriberProfile::getTemplateFamilyId,
                        Collectors.mapping(SubscriberProfile::getName, Collectors.toList())));
        return templateRepository.findAllByOrderByNameAscVersionNumberDesc().stream()
                .map(template -> toResponse(template, fields.getOrDefault(template.getId(), List.of()),
                        usedBy.getOrDefault(template.getFamilyId(), List.of())))
                .toList();
    }

    @Override
    public List<FormatField> fields(Long templateId) {
        return templateId == null ? List.of() : fieldRepository.findByTemplateIdOrderByPositionAsc(templateId);
    }

    @Override
    public Optional<FormatTemplate> inUse(Long familyId) {
        return familyId == null ? Optional.empty() : templateRepository.findFirstByFamilyIdAndActiveTrueOrderByVersionNumberDesc(familyId);
    }

    @Override
    public Optional<FormatTemplate> latest(Long familyId) {
        return familyId == null ? Optional.empty() : templateRepository.findFirstByFamilyIdOrderByVersionNumberDesc(familyId);
    }

    @Override
    public String checkChoice(Long familyId) {
        if (familyId == null) {
            throw new BadRequestException("Choose a list for this subscriber");
        }
        FormatTemplate latest = latest(familyId).orElseThrow(() -> new BadRequestException("Unknown list"));
        if (inUse(familyId).isEmpty()) {
            throw new BadRequestException("\"" + latest.getName() + "\" has no approved version yet. It can be chosen once a List Owner approves it.");
        }
        return latest.getName();
    }

    @Override
    public String templateName(Long familyId) {
        return latest(familyId).map(FormatTemplate::getName).orElse(null);
    }

    @Override
    public Map<Long, String> templateNames() {
        // Newest version first within each list, so each list gets its current name.
        Map<Long, String> names = new HashMap<>();
        templateRepository.findAllByOrderByNameAscVersionNumberDesc().stream()
                .filter(template -> template.getFamilyId() != null)
                .sorted(Comparator.comparingInt(FormatTemplate::getVersionNumber).reversed())
                .forEach(template -> names.putIfAbsent(template.getFamilyId(), template.getName()));
        return names;
    }

    // ------------------------------------------------------------------ saving

    @Transactional
    @Override
    public FormatTemplateResponse save(FormatTemplateRequest request) {
        String name = request.name().trim();
        List<FormatField> fields = checkFields(request.fields(), true);
        FormatTemplate latest = templateRepository.findFirstByNameIgnoreCaseOrderByVersionNumberDesc(name).orElse(null);
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
        Long templateId = template.getId();
        fields.forEach(field -> field.setTemplateId(templateId));
        fieldRepository.saveAll(fields);
        auditService.template(template.getId(), resubmission ? "TEMPLATE_RESUBMITTED" : "TEMPLATE_SUBMITTED",
                "\"" + template.getName() + "\" v" + template.getVersionNumber() + ": " + describe(fields));
        return toResponse(template, fields, usedBy(template.getFamilyId()));
    }

    @Override
    public List<FormatField> previewFields(List<FormatFieldRequest> fields) {
        return checkFields(fields, false);
    }

    /**
     * The columns of a list: each must be a column of a group table. Its link into the group is set from the groups
     * file (joinColumn = the table's key or foreign key, joinTarget = the table.column it joins to). The key of every
     * group used is always on the list: it is added in front of the group's first column when missing. With strict
     * headers (saving), export names are required and unique within a group (they become the group's XML elements).
     */
    private List<FormatField> checkFields(List<FormatFieldRequest> requests, boolean strictHeaders) {
        if (requests == null) {
            return List.of();
        }
        List<ResolvedGroup> groups = tableGroups.groups();
        List<FormatField> checked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Set<String>> elementsByGroup = new HashMap<>();
        Set<String> keyed = new LinkedHashSet<>();
        for (FormatFieldRequest request : requests) {
            String wanted = StringUtils.trimToEmpty(request.sourceTable());
            String wantedColumn = StringUtils.trimToEmpty(request.sourceColumn());
            ResolvedGroup resolved = groups.stream().filter(candidate -> candidate.group().has(wanted)).findFirst()
                    .orElseThrow(() -> new BadRequestException("Table " + wanted + " is not in any group of config/table-groups.yml"));
            TableGroup group = resolved.group();
            String groupTable = group.tables().stream().filter(name -> name.equalsIgnoreCase(wanted)).findFirst().orElseThrow();
            SourceTable table = resolved.table(groupTable);
            SourceTable.Column column = table.column(wantedColumn)
                    .orElseThrow(() -> new BadRequestException("Unknown column " + wantedColumn + " on " + table.name()));
            Set<String> elements = elementsByGroup.computeIfAbsent(group.name(), ignored -> new LinkedHashSet<>());
            if (keyed.add(group.name())) {
                String key = resolved.keyColumn();
                boolean present = requests.stream().anyMatch(other -> group.isRoot(StringUtils.trimToEmpty(other.sourceTable()))
                        && key.equalsIgnoreCase(StringUtils.trimToEmpty(other.sourceColumn())));
                if (!present) {
                    String rootTable = resolved.table(group.root().table()).name();
                    checked.add(field(rootTable, key, key, key, group.joinTarget(group.root().table())));
                    seen.add((rootTable + "." + key).toLowerCase(Locale.ROOT));
                    elements.add(key.toLowerCase(Locale.ROOT));
                }
            }
            String header = StringUtils.trimToEmpty(request.headerName());
            if (strictHeaders) {
                if (header.isEmpty()) {
                    throw new BadRequestException("Header name is required");
                }
                String label = table.name() + "." + column.name();
                if (!seen.add(label.toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException("Column " + label + " is already on the list");
                }
                String element = XmlNames.element(header);
                if (!elements.add(element.toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException("Two columns of group " + group.name() + " would both be <" + element
                            + "> in the XML. Give them names that differ by more than spaces or symbols.");
                }
            }
            checked.add(field(table.name(), column.name(), header.isEmpty() ? column.name() : header,
                    resolved.column(groupTable, group.joinColumn(groupTable)), group.joinTarget(groupTable)));
        }
        for (int index = 0; index < checked.size(); index++) {
            checked.get(index).setPosition(index + 1);
        }
        return checked;
    }

    private static FormatField field(String table, String column, String header, String joinColumn, String joinTarget) {
        FormatField field = new FormatField();
        field.setSourceTable(table);
        field.setSourceColumn(column);
        field.setHeaderName(header);
        field.setJoinColumn(joinColumn);
        field.setJoinTarget(joinTarget);
        return field;
    }

    private static String describe(List<FormatField> fields) {
        return String.join(", ", fields.stream()
                .map(field -> field.getSourceTable() + "." + field.getSourceColumn()
                        + (field.getSourceColumn().equals(field.getHeaderName()) ? "" : " as " + field.getHeaderName()))
                .toList());
    }

    // ------------------------------------------------------------------ approval

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
        auditService.template(template.getId(), "TEMPLATE_APPROVED", "\"" + template.getName() + "\" v" + template.getVersionNumber()
                + " is now in use" + (template.getReviewComment() == null ? "" : ". Comment: " + template.getReviewComment()));
        return toResponse(template);
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
        auditService.template(template.getId(), "TEMPLATE_CHANGES_REQUESTED", "v" + template.getVersionNumber() + " returned to "
                + template.getCreatedByName() + ". Comment: " + template.getReviewComment());
        return toResponse(template);
    }

    /** A version a List Owner may decide on now: waiting for review, and not submitted by them. */
    private FormatTemplate forDecision(Long id) {
        if (!CurrentUser.approver()) {
            throw new ForbiddenException("Only a List Owner can review a list");
        }
        FormatTemplate template = templateRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("List not found"));
        if ("SUPERSEDED".equals(template.getStatus())) {
            int newest = latest(template.getFamilyId()).map(FormatTemplate::getVersionNumber).orElse(template.getVersionNumber());
            throw new ConflictException("Version " + template.getVersionNumber() + " was replaced by version " + newest
                    + " before it was reviewed. Review the newest version.");
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

    // ------------------------------------------------------------------ responses

    private List<String> usedBy(Long familyId) {
        return familyId == null ? List.of() : subscriberRepository.findByTemplateFamilyIdOrderByNameAsc(familyId).stream()
                .map(SubscriberProfile::getName).toList();
    }

    private FormatTemplateResponse toResponse(FormatTemplate template) {
        return toResponse(template, fields(template.getId()), usedBy(template.getFamilyId()));
    }

    private static FormatTemplateResponse toResponse(FormatTemplate template, List<FormatField> fields, List<String> usedBy) {
        return new FormatTemplateResponse(template.getId(), template.getFamilyId(), template.getName(), template.getVersionNumber(),
                template.isActive(), template.getStatus(), template.getCreatedBy(), template.getCreatedByName(), template.getApprovedBy(),
                template.getApprovedByName(), template.getApprovedAt(), template.getCreatedAt(),
                fields.stream().map(field -> new FormatTemplateResponse.FieldResponse(field.getId(), field.getSourceTable(),
                        field.getSourceColumn(), field.getHeaderName(), field.getPosition(), field.getJoinColumn(), field.getJoinTarget())).toList(),
                usedBy, template.getReviewedBy(), template.getReviewedByName(), template.getReviewedAt(), template.getReviewComment());
    }
}
