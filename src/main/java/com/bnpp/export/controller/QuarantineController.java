package com.bnpp.export.controller;

import com.bnpp.export.dto.QuarantinePageResponse;
import com.bnpp.export.service.QuarantineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/** Records held back from files by the quality check. */
@RestController
@RequestMapping("/api/quarantine")
public class QuarantineController {

    private final QuarantineService quarantineService;

    public QuarantineController(QuarantineService quarantineService) {
        this.quarantineService = quarantineService;
    }

    /** A page of held records, newest first; {@code limit} is at most 100. */
    @GetMapping
    public QuarantinePageResponse quarantine(@RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "25") int limit) {
        return quarantineService.search(q, offset, limit);
    }
}
