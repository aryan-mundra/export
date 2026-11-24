package com.bnpp.export.controller;

import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.JobPageResponse;
import com.bnpp.export.service.ExportRunService;
import com.bnpp.export.service.JobService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sends (History): the list, one send in detail, each subscriber's file and its proof, and retrying a failed file. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;
    private final ExportRunService runService;

    public JobController(JobService jobs, ExportRunService runService) {
        this.jobs = jobs;
        this.runService = runService;
    }

    /** A page of sends, newest first; {@code limit} is at most 100. */
    @GetMapping
    public JobPageResponse jobs(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int offset,
                                @RequestParam(defaultValue = "25") int limit) {
        return jobs.jobs(q, offset, limit);
    }

    @GetMapping("/{id}")
    public JobDetailResponse job(@PathVariable Long id) {
        return jobs.job(id);
    }

    @PostMapping("/{jobId}/files/{fileId}/retry")
    public JobDetailResponse retry(@PathVariable Long jobId, @PathVariable Long fileId) {
        return runService.retry(jobId, fileId);
    }

    @PostMapping("/{jobId}/files/{fileId}/proof")
    public DeliveryProofResponse proof(@PathVariable Long jobId, @PathVariable Long fileId) {
        return jobs.proof(jobId, fileId);
    }

    @GetMapping("/{jobId}/files/{fileId}")
    public ResponseEntity<Resource> file(@PathVariable Long jobId, @PathVariable Long fileId) {
        return FileResponses.stored(jobs.file(jobId, fileId));
    }

    @GetMapping("/{jobId}/files/{fileId}/archive")
    public ResponseEntity<Resource> archive(@PathVariable Long jobId, @PathVariable Long fileId) {
        return FileResponses.stored(jobs.archive(jobId, fileId));
    }
}
