package com.bnpp.export.service;

import com.bnpp.export.dto.QuarantineResponse;

import java.util.List;

/** Records held back from files because they failed the quality check. */
public interface QuarantineService {

    /** Held records, newest first, searched by record, reason, subscriber or list version. */
    List<QuarantineResponse> search(String query);
}
