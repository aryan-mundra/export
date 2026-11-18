package com.bnpp.export.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExportFile {

    private String filePath;

    private String fileName;

    private long fileSize;

    private String fileHash;

    private String formatType;

    private LocalDateTime createdAt;
}
