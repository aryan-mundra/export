package com.bnpp.export.service;

import com.bnpp.export.entity.FormatField;

import java.util.List;
import java.util.Map;

/**
 * Adds golden-source values to list records. Every gs_ table is matched to the list entry on BNP_ID,
 * the key all gs_ tables share. When a table has several matching rows for one entry, the
 * distinct values are joined with "; " in a fixed order.
 *
 * Called once per batch while a file streams, so it only ever holds one batch.
 */
public interface GoldenSourceService {

    List<Map<String, Object>> enrich(List<Map<String, Object>> records, List<FormatField> fields);
}
