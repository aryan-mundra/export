package com.bnpp.export.service;

import com.bnpp.export.dto.ExportLatestResponse;
import com.bnpp.export.dto.ExportTemplatePageResponse;

/**
 * The Export page works by template: each approved template, its latest data version, and its
 * subscribers. Exporting the latest version reads the current data once: identical data keeps the
 * existing version, changed data becomes the next one. Lists are searched and paged on the server.
 */
public interface TemplateExportService {

    /**
     * Approved templates in use, searched by name, a page at a time.
     *
     * @param withVersions only templates that already have data versions (for the Previous Versions tab)
     */
    ExportTemplatePageResponse list(String query, boolean withVersions, int offset, int limit);

    /**
     * Reads the template's current data from the newest certified list version: identical data keeps
     * its existing version; changed data becomes the next one (e.g. 1.1, then 1.2).
     */
    ExportLatestResponse exportLatest(Long familyId);
}
