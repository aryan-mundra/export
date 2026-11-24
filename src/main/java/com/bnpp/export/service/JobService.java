package com.bnpp.export.service;

import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.JobPageResponse;

/** Sends as screens read them: dashboard, history, detail, delivery proof and file downloads. */
public interface JobService {

    DashboardResponse dashboard();

    /** A page of sends, newest first, searched by list name, user or status. */
    JobPageResponse jobs(String query, int offset, int limit);

    JobDetailResponse job(Long jobId);

    /** Whether a delivered file's archived copy still matches the hash recorded at delivery. */
    DeliveryProofResponse proof(Long jobId, Long fileId);

    /** The file built for one subscriber of a send. */
    FileDownload file(Long jobId, Long fileId);

    /** The archived copy of a delivered file. */
    FileDownload archive(Long jobId, Long fileId);

    /** The last file delivered to a subscriber. */
    FileDownload lastFile(Long subscriberId);
}
