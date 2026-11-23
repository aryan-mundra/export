package com.bnpp.export.service.impl;

import com.bnpp.export.config.MockProperties;
import com.bnpp.export.dto.SystemInfoResponse;
import com.bnpp.export.service.SystemService;
import org.springframework.stereotype.Service;

/** Implementation of {@link SystemService}. */
@Service
public class SystemServiceImpl implements SystemService {

    private final MockProperties mockProperties;

    public SystemServiceImpl(MockProperties mockProperties) {
        this.mockProperties = mockProperties;
    }

    @Override
    public SystemInfoResponse info() {
        boolean mock = mockProperties.isEnabled();
        return new SystemInfoResponse(mock, mock ? "h2" : "postgres", mock ? "H2" : "PostgreSQL");
    }
}
