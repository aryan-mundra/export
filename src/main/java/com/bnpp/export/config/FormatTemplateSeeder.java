package com.bnpp.export.config;

import com.bnpp.export.service.FormatTemplateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Two approved starter lists (Party Basic, Party Detail) built from GS_PARTY, for the automated tests.
 * Off unless both export.demo-data and export.demo-lists are true: the application starts with no lists,
 * and users create their own on the Create a List page.
 */
@Component
@Order(100)
@ConditionalOnProperty(name = {"export.demo-data", "export.demo-lists"}, havingValue = "true")
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
