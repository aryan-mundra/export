package com.bnpp.export.service;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.SubscriberProfile;

import java.util.Map;

/**
 * Writes ver_export_file (one row per built file) and ver_export_file_record (the list records in
 * each file, in file order). Records are written in batches while the file streams, so the manifest
 * never holds a whole file's records in memory.
 */
public interface ExportManifestService {

    /** The manifest of one file while it streams: its records are written in batches. */
    interface Manifest {

        /** Records one list record, in file order. */
        void add(Map<String, Object> record);

        /** Records the finished file's size and fingerprint. */
        void finish(ExportFile file);

        /** The file was not completed; the row stays as evidence of the attempt. */
        void fail();
    }
    /** Starts the manifest of a file that is about to be streamed. The row stays BUILDING until finished. */
    Manifest begin(ExportJob job,
                   ExportJobSubscriberStatus status,
                   SubscriberProfile subscriber,
                   String formatType,
                   String fileName,
                   String filePath);

    /** Files still BUILDING at start-up were interrupted; their rows are kept as FAILED evidence. */
    void failUnfinished();

    /** The file was delivered: its row becomes SENT, with the archive's path. */
    void markSent(String filePath, String archivePath);
}
