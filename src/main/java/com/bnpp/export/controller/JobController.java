package com.bnpp.export.controller;

import com.bnpp.export.dto.DeliveryProofResponse;
import com.bnpp.export.dto.JobDetailResponse;
import com.bnpp.export.dto.JobPageResponse;
import com.bnpp.export.service.ExportService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/** Export runs (History): list, detail, the files and proof of each subscriber, and acting on held or failed ones. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final ExportService exportService;

    public JobController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** A page of jobs, newest first; {@code limit} is at most 100. */
    @GetMapping
    public JobPageResponse jobs(@RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int offset,
                                @RequestParam(defaultValue = "25") int limit) {
        return exportService.listJobs(q, offset, limit);
    }

    @GetMapping("/{id}")
    public JobDetailResponse job(@PathVariable Long id) {
        return exportService.getJob(id);
    }

    @PostMapping("/{jobId}/subscribers/{statusId}/accept")
    public JobDetailResponse accept(@PathVariable Long jobId, @PathVariable Long statusId) {
        return exportService.acceptOmissions(jobId, statusId);
    }

    @PostMapping("/{jobId}/subscribers/{statusId}/retry")
    public JobDetailResponse retry(@PathVariable Long jobId, @PathVariable Long statusId) {
        return exportService.retry(jobId, statusId);
    }

    @PostMapping("/{jobId}/subscribers/{statusId}/proof")
    public DeliveryProofResponse proof(@PathVariable Long jobId, @PathVariable Long statusId) {
        return exportService.proof(jobId, statusId);
    }

    @GetMapping("/{jobId}/subscribers/{statusId}/file")
    public ResponseEntity<Resource> file(@PathVariable Long jobId, @PathVariable Long statusId) {
        return FileResponses.stored(exportService.download(jobId, statusId));
    }

    @GetMapping("/{jobId}/subscribers/{statusId}/archive")
    public ResponseEntity<Resource> archive(@PathVariable Long jobId, @PathVariable Long statusId) {
        return FileResponses.stored(exportService.downloadArchive(jobId, statusId));
    }
}
