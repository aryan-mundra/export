package com.bnpp.export.service;

import com.bnpp.export.dto.ExportFile;
import org.springframework.core.io.Resource;

import java.nio.file.Path;

/** StorageService. */
public interface StorageService {

    Path getRoot();

    /** Where a file is written while it is being built (temp) and where it ends up (target). */
    record PendingFile(Path temp, Path target, String fileName) {
    }

    /**
     * Reserves a file name. Writers stream into {@code temp()}; {@link #commit} moves it to its final
     * name in one step, so a half-written file never appears under a real export name.
     */
    PendingFile reserve(String subscriberName, Long versionId, String extension);

    /** Fingerprints the finished temp file in chunks and moves it to its final name. */
    ExportFile commit(PendingFile pending, String formatType);

    /** Deletes .part and .body files left by exports that were interrupted. Finished files are never touched. */
    void deleteUnfinishedFiles();

    /** Removes an unfinished file after a failure or when there was nothing to write. */
    void discard(PendingFile pending);

    String archive(String filePath, String fileName);

    /** SHA-256 of a file, read in chunks so files of any size can be fingerprinted. */
    String hashFile(String filePath);

    Resource load(String filePath);
}
