package com.bnpp.export.dto;

import com.bnpp.export.entity.CertifiedVersion;

import java.time.LocalDate;

/** A certified version of a source list that can be exported. */
public record CertifiedVersionResponse(
        Long id,
        Long versionId,
        String listCode,
        String listName,
        LocalDate contentAsOf,
        String status,
        boolean operatorVisible
) {
    public static CertifiedVersionResponse from(CertifiedVersion version) {
        return new CertifiedVersionResponse(version.getId(), version.getVersionId(), version.getListCode(), version.getListName(),
                version.getContentAsOf(), version.getStatus(), version.isOperatorVisible());
    }
}
