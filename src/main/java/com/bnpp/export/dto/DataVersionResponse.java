package com.bnpp.export.dto;

import com.bnpp.export.model.DataVersion;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A stored data version of a list.
 *
 * @param number  1, 2, 3... within the list
 * @param records records of every group
 * @param groups  records per group, in the order of the groups (root table name)
 */
public record DataVersionResponse(Long id, Long familyId, Long templateId, int templateVersion, String listName, int number,
                                  long records, Map<String, Long> groups, String createdBy, LocalDateTime createdAt) {

    public static DataVersionResponse from(DataVersion version) {
        return new DataVersionResponse(version.id(), version.familyId(), version.templateId(), version.templateVersion(),
                version.listName(), version.number(), version.records(), version.groups(), version.createdBy(), version.createdAt());
    }
}
