package com.bnpp.export.format;

import com.bnpp.export.service.RecordSelector;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class RecordBatchProvider {

    private final RecordSelector recordSelector;

    public RecordBatchProvider(RecordSelector recordSelector) {
        this.recordSelector = recordSelector;
    }

    public void stream(Long versionId,
                       String scope,
                       String exportMode,
                       LocalDate fromDate,
                       LocalDate toDate,
                       int batchSize,
                       Consumer<List<Map<String, Object>>> consumer) {
        int size = Math.max(1, batchSize);
        List<Map<String, Object>> buffer = new ArrayList<>();
        recordSelector.streamRecords(versionId, scope, exportMode, fromDate, toDate, incoming -> {
            for (Map<String, Object> row : incoming) {
                buffer.add(row);
                if (buffer.size() >= size) {
                    consumer.accept(List.copyOf(buffer));
                    buffer.clear();
                }
            }
        });
        if (!buffer.isEmpty()) {
            consumer.accept(List.copyOf(buffer));
        }
    }
}
