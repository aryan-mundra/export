package com.bnpp.export.controller;

import com.bnpp.export.dto.DashboardResponse;
import com.bnpp.export.dto.DatabaseSwitchRequest;
import com.bnpp.export.dto.DatabaseSwitchResponse;
import com.bnpp.export.dto.SystemInfoResponse;
import com.bnpp.export.service.JobService;
import com.bnpp.export.service.SystemService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard, which database the module runs on, and the Database switch. */
@RestController
@RequestMapping("/api")
public class SystemController {

    private final JobService jobs;
    private final SystemService system;

    public SystemController(JobService jobs, SystemService system) {
        this.jobs = jobs;
        this.system = system;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return jobs.dashboard();
    }

    @GetMapping("/system")
    public SystemInfoResponse system() {
        return system.info();
    }

    /** Saves the chosen database and restarts the module on it. */
    @PostMapping("/system/database")
    public DatabaseSwitchResponse switchDatabase(@Valid @RequestBody DatabaseSwitchRequest request) {
        return system.switchDatabase(request);
    }
}
