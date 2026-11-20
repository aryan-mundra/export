package com.bnpp.export.format;

import com.bnpp.export.model.FormatField;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Shared helpers for the streaming file writers. */
final class RecordValues {

    /** Large enough that writing millions of small rows is not dominated by system calls. */
    static final int BUFFER = 1 << 16;

    private RecordValues() {
    }

    /** The value of a template column in a record. */
    static Object value(Map<String, Object> record, FormatField field) {
        String key = field.recordKey();
        Object value = record.get(key);
        if (value == null && "record_date".equals(key)) {
            return record.get("dateOfBirth");
        }
        return value;
    }

    static Writer utf8Writer(Path path) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8), BUFFER);
    }
}
