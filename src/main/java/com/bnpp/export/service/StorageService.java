package com.bnpp.export.service;

import com.bnpp.export.model.ExportFile;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Service
public class StorageService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path root;

    public StorageService(@Value("${export.storage.directory:data/exports}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @PostConstruct
    void createRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create export directory " + root, ex);
        }
    }

    public Path getRoot() {
        return root;
    }

    public ExportFile store(String subscriberName, Long versionId, String formatType, String extension, byte[] content) {
        String safeName = subscriberName == null ? "subscriber" : subscriberName.replaceAll("[^A-Za-z0-9_-]+", "_");
        String fileName = safeName + "_v" + versionId + "_" + STAMP.format(LocalDateTime.now()) + "." + extension;
        Path directory = root.resolve(String.valueOf(versionId)).normalize();
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Export path escapes the storage directory");
        }
        try {
            Files.createDirectories(directory);
            Files.write(target, content);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write export file", ex);
        }
        ExportFile file = new ExportFile();
        file.setFilePath(target.toString());
        file.setFileName(fileName);
        file.setFileSize(content.length);
        file.setFileHash(sha256(content));
        file.setFormatType(formatType);
        file.setCreatedAt(LocalDateTime.now());
        return file;
    }

    public String archive(String filePath, String fileName) {
        Path source = Path.of(filePath).toAbsolutePath().normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Export file is not available");
        }
        Path directory = root.resolve("archive");
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Archive path escapes the storage directory");
        }
        try {
            Files.createDirectories(directory);
            if (!Files.exists(target)) {
                Files.copy(source, target);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to archive export file", ex);
        }
        return target.toString();
    }

    public String hashFile(String filePath) {
        try {
            return sha256(Files.readAllBytes(Path.of(filePath)));
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read export file", ex);
        }
    }

    public Resource load(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Export file is not available");
        }
        return new FileSystemResource(path);
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
