package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.dto.SourceTableResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatFilter;
import com.bnpp.export.entity.FormatTemplate;

import java.util.List;
import java.util.Map;

/** FormatTemplateService. */
public interface FormatTemplateService {

    /** Every gs_ table in the source database, with its columns. */
    List<SourceTableResponse> sources();

    List<FormatTemplateResponse> list();

    List<FormatField> fields(Long templateId);

    /** The approved version in use of a template (family), if it has one. */
    java.util.Optional<FormatTemplate> inUse(Long familyId);

    /**
     * Saves a new version for approval. A template has no file format: the format is chosen per subscriber
     * when a version is sent, so column names must work in every format. A name that already exists adds a version to
     * that template; a new name starts a new template at version 1. Nothing changes for subscribers
     * until an approver approves the version.
     *
     * A version still waiting for review is replaced (SUPERSEDED), so approvers only ever review the
     * newest submission. Saving after an approver requested changes is a resubmission.
     */
    FormatTemplateResponse save(FormatTemplateRequest request);

    /** The row filters of a template version, in order. */
    List<FormatFilter> filters(Long templateId);

    /** The row filters of a template version in words, for screens and messages. */
    List<String> filterSummaries(Long templateId);

    /**
     * Checks a template chosen for a subscriber: it must exist and have an approved version. Any template
     * can be sent in any format. Returns the template's name for messages and the audit log.
     */
    String checkChoice(Long familyId);

    /** The name of a template, for display; null when there is none. */
    String templateName(Long familyId);

    /** The name of every list (by family id), in one query. */
    Map<Long, String> templateNames();

    /**
     * Puts a version in use for its own template only; other templates are not affected. Its
     * subscribers get it from their next export. The comment is optional.
     */
    FormatTemplateResponse approve(Long id, String comment);

    FormatTemplateResponse approve(Long id);

    /** Closes a version for good. The version in use, if any, stays in use. A reason is required. */
    FormatTemplateResponse reject(Long id, String comment);

    /**
     * Returns a version to its maker, who edits it and resubmits it as a new version. The version
     * in use, if any, stays in use meanwhile. What to change is required.
     */
    FormatTemplateResponse requestChanges(Long id, String comment);

    /**
     * Demo databases only: starts CSV and XML off with an approved template built from GS_PARTY, and
     * gives every demo subscriber without a template the one for its format, so exports work out of
     * the box. Real databases get no templates; users build them from their gs_ tables.
     */
    void seedDemoTemplates();

    /**
     * Checks the source of each column but not the export names, which may be blank or repeated
     * while the user is still typing. Used by the data explorer.
     */
    List<FormatField> previewFields(List<FormatFieldRequest> fields);

    /** How a gs_ table's rows are matched to a list record: table column = record field. */
    record Link(String column, String field) {
    }

    /**
     * Every gs_ table is matched to a list entry on BNP_ID, the key all gs_ tables share. A table
     * without a BNP_ID column cannot be used. (Link values sent by a client are ignored.)
     */
    Link resolveLink(SourceCatalogService.SourceTable table, String joinColumn, String joinField);
}
