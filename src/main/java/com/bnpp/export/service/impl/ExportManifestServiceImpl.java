package com.bnpp.export.service.impl;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.ExportedFile;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.repository.ExportedFileRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.ExportManifestService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Implementation of {@link ExportManifestService}. */
@Service
public class ExportManifestServiceImpl implements ExportManifestService {

    private static final int BATCH = 1_000;

    private final ExportedFileRepository fileRepository;
    private final JdbcTemplate jdbcTemplate;

    public ExportManifestServiceImpl(ExportedFileRepository fileRepository, JdbcTemplate jdbcTemplate) {
        this.fileRepository = fileRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
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
        return new FileManifest(fileRepository.save(row));
    }

    @Transactional
    @Override
    public void failUnfinished() {
        for (ExportedFile row : fileRepository.findByStatus("BUILDING")) {
            row.setStatus("FAILED");
            fileRepository.save(row);
        }
    }

    @Transactional
    @Override
    public void markSent(String filePath, String archivePath) {
        fileRepository.findFirstByFilePathOrderByIdDesc(filePath).ifPresent(row -> {
            row.setStatus("SENT");
            row.setArchivePath(archivePath);
            row.setSentAt(LocalDateTime.now());
            fileRepository.save(row);
        });
    }

    private final class FileManifest implements Manifest {

        private final ExportedFile row;
        private final List<Object[]> lines = new ArrayList<>(BATCH);
        private int line;

        private FileManifest(ExportedFile row) {
            this.row = row;
        }

        @Override
        public void add(Map<String, Object> record) {
            Object id = record.get("id");
            Object action = record.get("action");
            lines.add(new Object[] {row.getId(), id == null ? "unknown" : String.valueOf(id),
                    action == null ? null : String.valueOf(action), ++line});
            if (lines.size() >= BATCH) {
                flush();
            }
        }

        @Override
        public void finish(ExportFile file) {
            flush();
            row.setFileSize(file.getFileSize());
            row.setFileHash(file.getFileHash());
            row.setRecordCount(line);
            row.setStatus("BUILT");
            fileRepository.save(row);
        }

        @Override
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
