package com.bnpp.export.controller;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.service.TemplatePreviewService;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.service.FormatTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/format-templates")
public class FormatTemplateController {

    private final FormatTemplateService formatTemplateService;
    private final TemplatePreviewService templatePreviewService;

    public FormatTemplateController(FormatTemplateService formatTemplateService,
                                    TemplatePreviewService templatePreviewService) {
        this.formatTemplateService = formatTemplateService;
        this.templatePreviewService = templatePreviewService;
    }

    @GetMapping
    public List<FormatTemplateResponse> list() {
        return formatTemplateService.list();
    }

    @GetMapping("/columns")
    public Map<String, List<String>> columns() {
        return Map.of("columns", FormatTemplateService.SOURCE_COLUMNS);
    }

    @GetMapping("/sources")
    public List<FormatTemplateService.SourceTableResponse> sources() {
        return formatTemplateService.sources();
    }

    @PostMapping("/preview")
    public TemplatePreviewService.Preview preview(@RequestBody PreviewRequest request) {
        return templatePreviewService.preview(request.fields());
    }

    public record PreviewRequest(List<FormatFieldRequest> fields) {
    }

    @PostMapping
    public FormatTemplateResponse save(@Valid @RequestBody FormatTemplateRequest request) {
        return formatTemplateService.save(request);
    }

    @PostMapping("/{id}/approve")
    public FormatTemplateResponse approve(@PathVariable Long id) {
        return formatTemplateService.approve(id);
    }
}
