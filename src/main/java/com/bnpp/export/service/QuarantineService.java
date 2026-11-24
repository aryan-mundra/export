package com.bnpp.export.service;

import com.bnpp.export.dto.QuarantinePageResponse;

/** Records held back from files because they failed the quality check. */
public interface QuarantineService {

    /** A page of held records, newest first, searched by record, reason, subscriber or list version. */
    QuarantinePageResponse search(String query, int offset, int limit);
}
