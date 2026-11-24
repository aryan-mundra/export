package com.bnpp.export.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A stored data version of a list (header only): which list version it was read with, its number, and how many
 * records each group holds.
 */
public record DataVersion(Long id, Long familyId, Long templateId, int templateVersion, String listName, int number,
                          long records, Map<String, Long> groups, String createdBy, LocalDateTime createdAt) {
}
