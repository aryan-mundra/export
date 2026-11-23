package com.bnpp.export.dto;

import org.springframework.core.io.Resource;

import java.nio.file.Path;

/**
 * A file a service hands to a controller for download: a stored file ({@code resource}), or a file built for
 * this download only ({@code temporaryFile}, deleted once it has been sent).
 */
public record FileDownload(String fileName, Resource resource, Path temporaryFile) {

    public static FileDownload stored(String fileName, Resource resource) {
        return new FileDownload(fileName, resource, null);
    }

    public static FileDownload temporary(String fileName, Path file) {
        return new FileDownload(fileName, null, file);
    }

    public boolean isTemporary() {
        return temporaryFile != null;
    }
}
