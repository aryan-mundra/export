package com.bnpp.export.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "export.mock")
public class MockProperties {

    private boolean enabled = true;

    private int recordsPerSubscriber = 400;
}
