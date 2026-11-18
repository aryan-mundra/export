package com.bnpp.export.format;

import java.util.List;
import java.util.Map;

public interface ExportFormat {

    String formatType();

    String fileExtension();

    byte[] generate(Long versionId, String subscriberName, List<Map<String, Object>> records);
}
