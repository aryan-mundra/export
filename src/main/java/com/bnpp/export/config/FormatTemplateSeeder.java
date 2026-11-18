package com.bnpp.export.config;

import com.bnpp.export.service.FormatTemplateService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class FormatTemplateSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final FormatTemplateService formatTemplateService;

    public FormatTemplateSeeder(FormatTemplateService formatTemplateService) {
        this.formatTemplateService = formatTemplateService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        formatTemplateService.seedDefaultCsv();
    }
}
