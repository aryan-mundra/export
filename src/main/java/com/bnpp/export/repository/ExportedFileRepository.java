package com.bnpp.export.repository;

import com.bnpp.export.entity.ExportedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExportedFileRepository extends JpaRepository<ExportedFile, Long> {

    Optional<ExportedFile> findFirstByFilePathOrderByIdDesc(String filePath);

    List<ExportedFile> findByExportJobIdOrderByIdAsc(Long exportJobId);

    List<ExportedFile> findByStatus(String status);
}
