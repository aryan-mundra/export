package com.bnpp.export.controller;

import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.SendRequest;
import com.bnpp.export.dto.SendReviewResponse;
import com.bnpp.export.dto.SynthesisRecordPageResponse;
import com.bnpp.export.dto.SynthesisRecordResponse;
import com.bnpp.export.dto.SynthesisSummaryResponse;
import com.bnpp.export.dto.TakeVersionResponse;
import com.bnpp.export.model.RecordChange;
import com.bnpp.export.service.DataVersionService;
import com.bnpp.export.service.SendService;
import com.bnpp.export.service.SynthesisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** Data versions of lists: taking one, its XML, Synthesis between two of them, and sending one to subscribers. */
@RestController
@RequestMapping("/api/data-versions")
public class DataVersionController {

    private final DataVersionService dataVersions;
    private final SynthesisService synthesis;
    private final SendService sends;

    public DataVersionController(DataVersionService dataVersions, SynthesisService synthesis, SendService sends) {
        this.dataVersions = dataVersions;
        this.synthesis = synthesis;
        this.sends = sends;
    }

    /** The versions of a list, newest first (every list when no familyId). */
    @GetMapping
    public List<DataVersionResponse> list(@RequestParam(required = false) Long familyId) {
        return dataVersions.versions(familyId);
    }

    /** Reads the list's data now: a new version when it changed, otherwise the newest one. */
    @PostMapping
    public TakeVersionResponse take(@RequestParam Long familyId) {
        return dataVersions.take(familyId);
    }

    @GetMapping("/{id}/xml")
    public ResponseEntity<StreamingResponseBody> xml(@PathVariable Long id) {
        return FileResponses.temporary(dataVersions.xml(id));
    }

    @GetMapping("/{after}/compare/{before}/summary")
    public SynthesisSummaryResponse summary(@PathVariable Long after, @PathVariable Long before) {
        return synthesis.summary(after, before);
    }

    /** @param change CHANGES (default: added, removed or changed), ADDED, REMOVED, CHANGED, UNCHANGED or ALL */
    @GetMapping("/{after}/compare/{before}/records")
    public SynthesisRecordPageResponse records(@PathVariable Long after, @PathVariable Long before, @RequestParam String group,
                                               @RequestParam(defaultValue = "CHANGES") RecordChange.Filter change,
                                               @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int offset,
                                               @RequestParam(defaultValue = "50") int limit) {
        return synthesis.records(after, before, group, change, q, offset, limit);
    }

    @GetMapping("/{after}/compare/{before}/record")
    public SynthesisRecordResponse record(@PathVariable Long after, @PathVariable Long before, @RequestParam String group,
                                          @RequestParam String key) {
        return synthesis.record(after, before, group, key);
    }

    /** Who would receive a version (default: the active subscribers of its list) and the review token. */
    @PostMapping("/{id}/send/review")
    public SendReviewResponse review(@PathVariable Long id, @RequestBody(required = false) SendRequest request) {
        return sends.review(id, request == null ? null : request.subscriberIds());
    }

    /** Sends a version to the reviewed subscribers: one XML file each, delivered in the background. */
    @PostMapping("/{id}/send")
    public ExportJobResponse send(@PathVariable Long id, @RequestBody SendRequest request) {
        return sends.send(id, request);
    }
}
