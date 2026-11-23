package com.bnpp.export.controller;

import com.bnpp.export.dto.ExplorePageResponse;
import com.bnpp.export.dto.ExploreRequest;
import com.bnpp.export.dto.FacetRequest;
import com.bnpp.export.dto.FacetResponse;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.dto.ReviewDecisionRequest;
import com.bnpp.export.dto.SourceTableResponse;
import com.bnpp.export.service.DataExplorerService;
import com.bnpp.export.service.FormatTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lists (Create a List): their versions, the gs_ tables to pick from, previewing the data, and the approver's review. */
@RestController
@RequestMapping("/api/format-templates")
public class FormatTemplateController {

    private final FormatTemplateService formatTemplateService;
    private final DataExplorerService dataExplorerService;

    public FormatTemplateController(FormatTemplateService formatTemplateService, DataExplorerService dataExplorerService) {
        this.formatTemplateService = formatTemplateService;
        this.dataExplorerService = dataExplorerService;
    }

    @GetMapping
    public List<FormatTemplateResponse> list() {
        return formatTemplateService.list();
    }

    @GetMapping("/sources")
    public List<SourceTableResponse> sources() {
        return formatTemplateService.sources();
    }

    /** A page of the rows a list (still being edited) would put in a file, with its row filters applied. */
    @PostMapping("/explore")
    public ExplorePageResponse explore(@RequestBody ExploreRequest request) {
        return dataExplorerService.explore(request);
    }

    /** The values of one column, for its filter. */
    @PostMapping("/explore/facet")
    public FacetResponse facet(@RequestBody FacetRequest request) {
        return dataExplorerService.facet(request);
    }

    @PostMapping
    public FormatTemplateResponse save(@Valid @RequestBody FormatTemplateRequest request) {
        return formatTemplateService.save(request);
    }

    @PostMapping("/{id}/approve")
    public FormatTemplateResponse approve(@PathVariable Long id, @RequestBody(required = false) ReviewDecisionRequest decision) {
        return formatTemplateService.approve(id, comment(decision));
    }

    @PostMapping("/{id}/reject")
    public FormatTemplateResponse reject(@PathVariable Long id, @RequestBody(required = false) ReviewDecisionRequest decision) {
        return formatTemplateService.reject(id, comment(decision));
    }

    @PostMapping("/{id}/request-changes")
    public FormatTemplateResponse requestChanges(@PathVariable Long id, @RequestBody(required = false) ReviewDecisionRequest decision) {
        return formatTemplateService.requestChanges(id, comment(decision));
    }

    private static String comment(ReviewDecisionRequest decision) {
        return decision == null ? null : decision.comment();
    }
}
