package com.bnpp.export.dto;

import java.util.List;

/** A page of held records (Quarantine), newest first: {@code total} records match, this page starts at {@code offset}. */
public record QuarantinePageResponse(long total, int offset, List<QuarantineResponse> records) {
}
