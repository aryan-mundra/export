package com.bnpp.export.controller;

import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.SystemInfoResponse;
import com.bnpp.export.service.ExportService;
import com.bnpp.export.service.SystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard and which database the module runs on. */
@RestController
@RequestMapping("/api")
public class SystemController {

    private final ExportService exportService;
    private final SystemService systemService;

    public SystemController(ExportService exportService, SystemService systemService) {
        this.exportService = exportService;
        this.systemService = systemService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return exportService.dashboard();
    }

    @GetMapping("/system")
    public SystemInfoResponse system() {
        return systemService.info();
    }
}
