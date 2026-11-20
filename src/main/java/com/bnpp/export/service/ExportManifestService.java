package com.bnpp.export.service;

import com.bnpp.export.model.ExportFile;
import com.bnpp.export.model.ExportJob;
import com.bnpp.export.model.ExportJobSubscriberStatus;
import com.bnpp.export.model.ExportedFile;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.ExportedFileRepository;
import com.bnpp.export.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes ver_export_file (one row per built file) and ver_export_file_record (the list records in
 * each file, in file order). Records are written in batches while the file streams, so the manifest
 * never holds a whole file's records in memory.
 */
@Service
public class ExportManifestService {

    private static final int BATCH = 1_000;

    private final ExportedFileRepository fileRepository;
    private final JdbcTemplate jdbcTemplate;

    public ExportManifestService(ExportedFileRepository fileRepository, JdbcTemplate jdbcTemplate) {
        this.fileRepository = fileRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Starts the manifest of a file that is about to be streamed. The row stays BUILDING until finished. */
    public Manifest begin(ExportJob job,
                          ExportJobSubscriberStatus status,
                          SubscriberProfile subscriber,
                          String formatType,
                          String fileName,
                          String filePath) {
        ExportedFile row = new ExportedFile();
        row.setExportJobId(job.getId());
        row.setJobSubscriberId(status.getId());
        row.setSubscriberId(subscriber.getId());
        row.setVersionId(job.getVersionId());
        row.setFormatType(formatType);
        row.setExportMode(subscriber.getExportMode());
        row.setFormatTemplateId(status.getFormatTemplateId());
        row.setFormatTemplateVersion(status.getFormatTemplateVersion());
        row.setFileName(fileName);
        row.setFilePath(filePath);
        row.setStatus("BUILDING");
        row.setCreatedBy(CurrentUser.actor());
        return new Manifest(fileRepository.save(row));
    }

    /** Files still BUILDING at start-up were interrupted; their rows are kept as FAILED evidence. */
    @Transactional
    public void failUnfinished() {
        for (ExportedFile row : fileRepository.findByStatus("BUILDING")) {
            row.setStatus("FAILED");
            fileRepository.save(row);
        }
    }

    @Transactional
    public void markSent(String filePath, String archivePath) {
        fileRepository.findFirstByFilePathOrderByIdDesc(filePath).ifPresent(row -> {
            row.setStatus("SENT");
            row.setArchivePath(archivePath);
            row.setSentAt(LocalDateTime.now());
            fileRepository.save(row);
        });
    }

    public final class Manifest {

        private final ExportedFile row;
        private final List<Object[]> lines = new ArrayList<>(BATCH);
        private int line;

        private Manifest(ExportedFile row) {
            this.row = row;
        }

        public void add(Map<String, Object> record) {
            Object id = record.get("id");
            Object action = record.get("action");
            lines.add(new Object[] {row.getId(), id == null ? "unknown" : String.valueOf(id),
                    action == null ? null : String.valueOf(action), ++line});
            if (lines.size() >= BATCH) {
                flush();
            }
        }

        /** Records the finished file's size and fingerprint. */
        public void finish(ExportFile file) {
            flush();
            row.setFileSize(file.getFileSize());
            row.setFileHash(file.getFileHash());
            row.setRecordCount(line);
            row.setStatus("BUILT");
            fileRepository.save(row);
        }

        /** The file was not completed; the row stays as evidence of the attempt. */
        public void fail() {
            lines.clear();
            row.setStatus("FAILED");
            row.setRecordCount(line);
            fileRepository.save(row);
        }

        private void flush() {
            if (lines.isEmpty()) {
                return;
            }
            jdbcTemplate.batchUpdate(
                    "INSERT INTO ver_export_file_record (file_id, record_id, action, line_number) VALUES (?, ?, ?, ?)",
                    lines);
            lines.clear();
        }
    }
}
