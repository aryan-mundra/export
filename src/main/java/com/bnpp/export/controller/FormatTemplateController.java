package com.bnpp.export.controller;

import com.bnpp.export.dto.FormatTemplateRequest;
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

    public FormatTemplateController(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    @GetMapping
    public List<FormatTemplateResponse> list() {
        return formatTemplateService.list();
    }

    @GetMapping("/columns")
    public Map<String, List<String>> columns() {
        return Map.of("columns", FormatTemplateService.SOURCE_COLUMNS);
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
