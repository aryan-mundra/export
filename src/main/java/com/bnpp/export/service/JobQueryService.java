package com.bnpp.export.service;

import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.DeliveryReportResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.JobPageResponse;


/** Reading export jobs and their files: dashboard, history, job detail, delivery report, proofs and downloads. */
public interface JobQueryService {

    DashboardResponse dashboard();

    /** A page of export jobs (History), newest first, searched by version, user, status or list name. */
    JobPageResponse listJobs(String query, int offset, int limit);

    JobDetailResponse getJob(Long jobId);

    DeliveryReportResponse latestReport(Long versionId);

    DeliveryProofResponse proof(Long jobId, Long statusId);

    /** The file built for one subscriber of a job. */
    FileDownload download(Long jobId, Long statusId);

    /** The archived copy of a file that was sent. */
    FileDownload downloadArchive(Long jobId, Long statusId);

    /** The last file delivered to a subscriber. */
    FileDownload downloadLastSubscriberFile(Long subscriberId);

    /** A small sample file for a subscriber, in its own format, from the newest certified version. */
    FileDownload sample(Long subscriberId);
}
