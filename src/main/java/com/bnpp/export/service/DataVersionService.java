package com.bnpp.export.service;

import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.TakeVersionResponse;
import com.bnpp.export.model.DataVersion;
import com.bnpp.export.model.VersionLayout;

import java.io.Writer;
import java.util.List;

/**
 * Data versions of lists: the list's data at a moment, stored so it can be sent, compared and sent again exactly.
 * A new version is stored only when the data changed.
 */
public interface DataVersionService {

    /** Reads the list's current data: a new version when it changed, otherwise the newest one. */
    TakeVersionResponse take(Long familyId);

    /** The versions of a list, newest first (every list when familyId is null). */
    List<DataVersionResponse> versions(Long familyId);

    DataVersion get(Long id);

    /** What a version was built from: its groups, tables, columns and XML names. */
    VersionLayout layout(Long id);

    /** A stored version as group XML. */
    FileDownload xml(Long id);

    /** Writes a stored version as group XML; returns the number of records. */
    long writeXml(Long id, Writer out);
}
