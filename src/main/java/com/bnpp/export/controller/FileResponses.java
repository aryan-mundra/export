package com.bnpp.export.controller;

import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.exception.ResourceNotFoundException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Turns a {@link FileDownload} from a service into an attachment response. */
final class FileResponses {

    private FileResponses() {
    }

    /** A stored file. */
    static ResponseEntity<Resource> stored(FileDownload download) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(download.fileName()))
                .contentType(mediaType(download.fileName()))
                .body(download.resource());
    }

    /** A file built for this download: streamed, then deleted. */
    static ResponseEntity<StreamingResponseBody> temporary(FileDownload download) {
        Path file = download.temporaryFile();
        StreamingResponseBody body = out -> {
            try {
                Files.copy(file, out);
            } finally {
                Files.deleteIfExists(file);
            }
        };
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition(download.fileName()))
                    .contentLength(Files.size(file))
                    .contentType(mediaType(download.fileName()))
                    .body(body);
        } catch (IOException ex) {
            throw new ResourceNotFoundException("The file could not be prepared");
        }
    }

    private static String disposition(String fileName) {
        return "attachment; filename=\"" + fileName + "\"";
    }

    private static MediaType mediaType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".csv")) {
            return MediaType.parseMediaType("text/csv");
        }
        if (name.endsWith(".txt")) {
            return MediaType.parseMediaType("text/plain;charset=UTF-8");
        }
        return MediaType.APPLICATION_XML;
    }
}
