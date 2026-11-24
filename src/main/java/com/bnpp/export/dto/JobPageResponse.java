package com.bnpp.export.dto;

import java.util.List;

/** A page of export jobs (History), newest first: {@code total} jobs match, this page starts at {@code offset}. */
public record JobPageResponse(long total, int offset, List<DashboardResponse.JobSummary> jobs) {
}
