package com.bnpp.export.format;

import com.bnpp.export.model.FormatField;
import com.bnpp.export.service.FormatTemplateService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
    public byte[] generate(Long versionId, String subscriberName, List<Map<String, Object>> records) {
        return generate(versionId, subscriberName, records, null);
    }

    public byte[] generate(Long versionId, String subscriberName, List<Map<String, Object>> records, Long templateId) {
        List<FormatField> fields = templateId == null
                ? formatTemplateService.activeFields("CSV")
                : formatTemplateService.fields(templateId);
        if (fields.isEmpty()) {
            fields = List.of(field("id", "id", 1), field("action", "action", 2), field("record_date", "record_date", 3));
        }
        String[] headers = fields.stream().map(FormatField::getHeaderName).toArray(String[]::new);
        StringWriter writer = new StringWriter();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(headers)
                .setCommentMarker('#')
                .build();
        try (CSVPrinter printer = new CSVPrinter(writer, format)) {
            printer.printComment("versionId=" + versionId + " subscriber=" + subscriberName);
            for (Map<String, Object> record : records) {
                Object[] values = new Object[fields.size()];
                for (int index = 0; index < fields.size(); index++) {
                    values[index] = value(record, fields.get(index).getSourceColumn());
                }
                printer.printRecord(values);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to build CSV export", ex);
        }
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Object value(Map<String, Object> record, String column) {
        if (record.containsKey(column) && record.get(column) != null) {
            return record.get(column);
        }
        if ("record_date".equals(column)) {
            return record.get("dateOfBirth");
        }
        return record.get(column);
    }

    private FormatField field(String source, String header, int position) {
        FormatField field = new FormatField();
        field.setSourceColumn(source);
        field.setHeaderName(header);
        field.setPosition(position);
        return field;
    }
}
