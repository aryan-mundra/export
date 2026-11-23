package com.bnpp.export.repository;

import com.bnpp.export.entity.ExportedFileRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportedFileRecordRepository extends JpaRepository<ExportedFileRecord, Long> {

    List<ExportedFileRecord> findByFileIdOrderByLineNumberAsc(Long fileId);

    List<ExportedFileRecord> findByRecordIdOrderByIdAsc(String recordId);
}
