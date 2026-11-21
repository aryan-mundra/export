package com.bnpp.export.controller;

import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
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

@RestController
@RequestMapping("/api/format-templates")
public class FormatTemplateController {

    private final FormatTemplateService formatTemplateService;
    private final DataExplorerService dataExplorerService;

    public FormatTemplateController(FormatTemplateService formatTemplateService,
                                    DataExplorerService dataExplorerService) {
        this.formatTemplateService = formatTemplateService;
        this.dataExplorerService = dataExplorerService;
    }

    @GetMapping
    public List<FormatTemplateResponse> list() {
        return formatTemplateService.list();
    }

    @GetMapping("/sources")
    public List<FormatTemplateService.SourceTableResponse> sources() {
        return formatTemplateService.sources();
    }

    /** A page of the rows a template (still being edited) would put in a file, with its row filters applied. */
    @PostMapping("/explore")
    public DataExplorerService.Page explore(@RequestBody DataExplorerService.ExploreRequest request) {
        return dataExplorerService.explore(request);
    }

    /** The values of one column, for its filter. */
    @PostMapping("/explore/facet")
    public DataExplorerService.Facet facet(@RequestBody DataExplorerService.FacetRequest request) {
        return dataExplorerService.facet(request);
    }

    @PostMapping
    public FormatTemplateResponse save(@Valid @RequestBody FormatTemplateRequest request) {
        return formatTemplateService.save(request);
    }

    /** An approver's decision on a version, with a comment (required to reject or request changes). */
    public record Decision(String comment) {
    }

    @PostMapping("/{id}/approve")
    public FormatTemplateResponse approve(@PathVariable Long id, @RequestBody(required = false) Decision decision) {
        return formatTemplateService.approve(id, decision == null ? null : decision.comment());
    }

    @PostMapping("/{id}/reject")
    public FormatTemplateResponse reject(@PathVariable Long id, @RequestBody(required = false) Decision decision) {
        return formatTemplateService.reject(id, decision == null ? null : decision.comment());
    }

    @PostMapping("/{id}/request-changes")
    public FormatTemplateResponse requestChanges(@PathVariable Long id, @RequestBody(required = false) Decision decision) {
        return formatTemplateService.requestChanges(id, decision == null ? null : decision.comment());
    }
}
