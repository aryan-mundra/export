package com.bnpp.export.format;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * A file format. Files are streamed: records are written one at a time and never held in memory
 * together, so file size is limited by disk space, not by the Java heap.
 */
public interface ExportFormat {

    String formatType();

    String fileExtension();

    /**
     * Opens a writer that streams records into {@code target}.
     *
     * @param templateId the template whose columns to write, or null for the format's template in use
     */
    RecordWriter open(Path target, Long versionId, String subscriberName, Long templateId) throws IOException;

    interface RecordWriter extends Closeable {

        void write(Map<String, Object> record) throws IOException;

        /** Records written so far. */
        long count();

        /** Completes the file. A writer closed without finish() leaves an unfinished file for the caller to discard. */
        void finish() throws IOException;
    }
}
