package com.bnpp.export.controller;

import com.bnpp.export.dto.SubscriberPageResponse;
import com.bnpp.export.dto.SubscriberRequest;
import com.bnpp.export.dto.SubscriberResponse;
import com.bnpp.export.service.ExportService;
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

/** Subscribers: list, search, add, change, approve a delivery endpoint, delete, and their files. */
@RestController
@RequestMapping("/api/subscribers")
public class SubscriberController {

    private final SubscriberService subscriberService;
    private final ExportService exportService;

    public SubscriberController(SubscriberService subscriberService, ExportService exportService) {
        this.subscriberService = subscriberService;
        this.exportService = exportService;
    }

    @GetMapping
    public List<SubscriberResponse> list(@RequestParam(required = false) String q) {
        return subscriberService.list(q);
    }

    /** Active subscribers, searched and paged, optionally of one format or one list. */
    @GetMapping("/search")
    public SubscriberPageResponse search(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String format,
                                         @RequestParam(required = false) Long templateFamilyId,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "25") int limit) {
        return subscriberService.search(q, format, templateFamilyId, offset, limit);
    }

    @PostMapping
    public SubscriberResponse create(@Valid @RequestBody SubscriberRequest request) {
        return subscriberService.create(request);
    }

    @PutMapping("/{id}")
    public SubscriberResponse update(@PathVariable Long id, @Valid @RequestBody SubscriberRequest request) {
        return subscriberService.update(id, request);
    }

    @PostMapping("/{id}/approve-endpoint")
    public SubscriberResponse approveEndpoint(@PathVariable Long id) {
        return subscriberService.approveEndpoint(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        subscriberService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sample")
    public ResponseEntity<Resource> sample(@PathVariable Long id) {
        return FileResponses.stored(exportService.sample(id));
    }

    @GetMapping("/{id}/last-file")
    public ResponseEntity<Resource> lastFile(@PathVariable Long id) {
        return FileResponses.stored(exportService.downloadLastSubscriberFile(id));
    }
}
