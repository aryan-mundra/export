package com.bnpp.export.service;

import com.bnpp.export.model.ExportFile;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Service
public class StorageService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

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

    /** Where a file is written while it is being built (temp) and where it ends up (target). */
    public record PendingFile(Path temp, Path target, String fileName) {
    }

    /**
     * Reserves a file name. Writers stream into {@code temp()}; {@link #commit} moves it to its final
     * name in one step, so a half-written file never appears under a real export name.
     */
    public PendingFile reserve(String subscriberName, Long versionId, String extension) {
        String safeName = subscriberName == null ? "subscriber" : subscriberName.replaceAll("[^A-Za-z0-9_-]+", "_");
        String fileName = safeName + "_v" + versionId + "_" + STAMP.format(LocalDateTime.now()) + "." + extension;
        Path directory = root.resolve(String.valueOf(versionId)).normalize();
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Export path escapes the storage directory");
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create export directory", ex);
        }
        return new PendingFile(directory.resolve(fileName + ".part"), target, fileName);
    }

    /** Fingerprints the finished temp file in chunks and moves it to its final name. */
    public ExportFile commit(PendingFile pending, String formatType) {
        try {
            String hash = hashPath(pending.temp());
            long size = Files.size(pending.temp());
            try {
                Files.move(pending.temp(), pending.target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(pending.temp(), pending.target(), StandardCopyOption.REPLACE_EXISTING);
            }
            ExportFile file = new ExportFile();
            file.setFilePath(pending.target().toString());
            file.setFileName(pending.fileName());
            file.setFileSize(size);
            file.setFileHash(hash);
            file.setFormatType(formatType);
            file.setCreatedAt(LocalDateTime.now());
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to finish export file", ex);
        }
    }

    /** Deletes .part and .body files left by exports that were interrupted. Finished files are never touched. */
    public void deleteUnfinishedFiles() {
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".part") || name.endsWith(".body") || name.endsWith(".staging");
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // try again at the next start
                        }
                    });
        } catch (IOException ignored) {
            // the storage directory is checked when it is created
        }
    }

    /** Removes an unfinished file after a failure or when there was nothing to write. */
    public void discard(PendingFile pending) {
        try {
            Files.deleteIfExists(pending.temp());
        } catch (IOException ex) {
            // Leftover .part files are harmless and never served; nothing else to do.
        }
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

    /** SHA-256 of a file, read in chunks so files of any size can be fingerprinted. */
    public String hashFile(String filePath) {
        try {
            return hashPath(Path.of(filePath));
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read export file", ex);
        }
    }

    private String hashPath(Path path) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(path)) {
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public Resource load(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Export file is not available");
        }
        return new FileSystemResource(path);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
