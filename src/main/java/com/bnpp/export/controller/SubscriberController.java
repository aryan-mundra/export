package com.bnpp.export.controller;

import com.bnpp.export.dto.SubscriberRequest;
import com.bnpp.export.dto.SubscriberResponse;
import com.bnpp.export.service.JobService;
import com.bnpp.export.service.SubscriberService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Subscribers: list, add, change, approve a delivery endpoint, delete, and their last file. */
@RestController
@RequestMapping("/api/subscribers")
public class SubscriberController {

    private final SubscriberService subscribers;
    private final JobService jobs;

    public SubscriberController(SubscriberService subscribers, JobService jobs) {
        this.subscribers = subscribers;
        this.jobs = jobs;
    }

    /** The formats a subscriber can be given. */
    @GetMapping("/formats")
    public List<String> formats() {
        return subscribers.formats();
    }

    @GetMapping
    public List<SubscriberResponse> list(@RequestParam(required = false) String q) {
        return subscribers.list(q);
    }

    @PostMapping
    public SubscriberResponse create(@Valid @RequestBody SubscriberRequest request) {
        return subscribers.create(request);
    }

    @PutMapping("/{id}")
    public SubscriberResponse update(@PathVariable Long id, @Valid @RequestBody SubscriberRequest request) {
        return subscribers.update(id, request);
    }

    @PostMapping("/{id}/approve-endpoint")
    public SubscriberResponse approveEndpoint(@PathVariable Long id) {
        return subscribers.approveEndpoint(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        subscribers.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/last-file")
    public ResponseEntity<Resource> lastFile(@PathVariable Long id) {
        return FileResponses.stored(jobs.lastFile(id));
    }
}
