package com.bnpp.export.service;

import com.bnpp.export.dto.ColumnValuesResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.GroupPreviewResponse;
import com.bnpp.export.entity.FormatField;

import java.io.Writer;
import java.util.List;

/** A list's current data, read from its table groups: a preview per group, or the whole list as group XML. */
public interface GroupExportService {

    /** One preview per group the columns use: the key, the chosen columns and a page of records. */
    List<GroupPreviewResponse> preview(List<FormatFieldRequest> fields, int offset, int limit);

    /**
     * The distinct values a column of a group table holds, as text, to offer as filter choices. {@code contains} narrows
     * them (case-insensitive); at most {@value #MAX_VALUES} are returned and {@code truncated} says there were more.
     */
    ColumnValuesResponse columnValues(String table, String column, String contains);

    int MAX_VALUES = 200;

    /** A list version's current data as group XML. */
    FileDownload xml(Long templateId);

    /** Writes every record of every group the columns use, as group XML. Returns the number of records. */
    long writeXml(String listName, Integer listVersion, List<FormatField> fields, Writer out);
}
