package com.bnpp.export.config;

import com.bnpp.export.service.FormatTemplateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Demo databases only: approved starter templates built from GS_PARTY. Runs after the demo
 * gs_ tables are created. Real databases start with no templates.
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "export.demo-data", havingValue = "true")
public class FormatTemplateSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final FormatTemplateService formatTemplateService;

    public FormatTemplateSeeder(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        formatTemplateService.seedDemoTemplates();
    }
}
