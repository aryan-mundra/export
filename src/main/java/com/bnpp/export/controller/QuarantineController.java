package com.bnpp.export.controller;

import com.bnpp.export.dto.QuarantineResponse;
import com.bnpp.export.service.QuarantineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Records held back from files by the quality check. */
@RestController
@RequestMapping("/api/quarantine")
public class QuarantineController {

    private final QuarantineService quarantineService;

    public QuarantineController(QuarantineService quarantineService) {
        this.quarantineService = quarantineService;
    }

    @GetMapping
    public List<QuarantineResponse> quarantine(@RequestParam(required = false) String q) {
        return quarantineService.search(q);
    }
}
