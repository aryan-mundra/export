package com.bnpp.export.service;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lists: their columns (from the table groups), versions and approval. Saving creates a new version for approval;
 * subscribers keep the version in use until a List Owner approves the new one.
 */
public interface FormatTemplateService {

    /** Every version of every list, newest first per list. */
    List<FormatTemplateResponse> list();

    /** The columns of a list version, in order. */
    List<FormatField> fields(Long templateId);

    /** The approved version in use of a list, if it has one. */
    Optional<FormatTemplate> inUse(Long familyId);

    /** The newest version of a list (approved or not). */
    Optional<FormatTemplate> latest(Long familyId);

    /**
     * Saves a new version for approval. A name that already exists adds a version to that list. A version still
     * waiting for review is replaced (SUPERSEDED); saving after changes were requested is a resubmission.
     */
    FormatTemplateResponse save(FormatTemplateRequest request);

    /** The columns of a list being edited, checked against the groups (export names may still be blank or repeated). */
    List<FormatField> previewFields(List<FormatFieldRequest> fields);

    /** Checks a list chosen for a subscriber (it must have an approved version) and returns its name. */
    String checkChoice(Long familyId);

    /** A list's name, or null. */
    String templateName(Long familyId);

    /** Every list's name by family id, in one query. */
    Map<Long, String> templateNames();

    /** Puts a version in use; the comment is optional. */
    FormatTemplateResponse approve(Long id, String comment);

    /** Closes a version for good; the version in use stays. A reason is required. */
    FormatTemplateResponse reject(Long id, String comment);

    /** Returns a version to its List Manager to change and resubmit. What to change is required. */
    FormatTemplateResponse requestChanges(Long id, String comment);
}
