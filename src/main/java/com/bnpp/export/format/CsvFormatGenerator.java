package com.bnpp.export.format;

import com.bnpp.export.model.FormatField;
import com.bnpp.export.service.FormatTemplateService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Plain RFC 4180 CSV: one header row, then one row per record, UTF-8. No comment lines, because most
 * CSV readers would take them for data. The version and subscriber are in the file name.
 * Rows are written as they arrive, so the file is never held in memory.
 */
@Component
public class CsvFormatGenerator implements ExportFormat {

    private final FormatTemplateService formatTemplateService;

    public CsvFormatGenerator(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    @Override
    public String formatType() {
        return "CSV";
    }

    @Override
    public String fileExtension() {
        return "csv";
    }

    @Override
    public RecordWriter open(Path target, Long versionId, String subscriberName, Long templateId) throws IOException {
        List<FormatField> fields = formatTemplateService.fields(templateId);
        if (fields.isEmpty()) {
            throw new IllegalStateException("No approved CSV template for this subscriber. Choose one on the Subscribers page.");
        }
        List<FormatField> columns = fields;
        String[] headers = columns.stream().map(FormatField::getHeaderName).toArray(String[]::new);
        CSVPrinter printer = new CSVPrinter(RecordValues.utf8Writer(target), CSVFormat.DEFAULT.builder().setHeader(headers).build());
        return new RecordWriter() {
            private long count;

            @Override
            public void write(Map<String, Object> record) throws IOException {
                Object[] values = new Object[columns.size()];
                for (int index = 0; index < columns.size(); index++) {
                    values[index] = RecordValues.value(record, columns.get(index));
                }
                printer.printRecord(values);
                count++;
            }

            @Override
            public long count() {
                return count;
            }

            @Override
            public void finish() throws IOException {
                printer.flush();
            }

            @Override
            public void close() throws IOException {
                printer.close();
            }
        };
    }
}
