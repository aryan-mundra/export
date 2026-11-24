package com.bnpp.export.config;

import com.bnpp.export.service.ExportRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * At start-up, marks exports that were running when the application stopped as FAILED, so they
 * do not show "In progress" forever and their subscribers can be retried.
 * Turn off with export.recover-interrupted=false when several instances share one database.
 */
@Component
@ConditionalOnProperty(name = "export.recover-interrupted", havingValue = "true", matchIfMissing = true)
public class InterruptedExportRecovery implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(InterruptedExportRecovery.class);

    private final ExportRunService runService;

    public InterruptedExportRecovery(ExportRunService runService) {
        this.runService = runService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int recovered = runService.recoverInterrupted();
        if (recovered > 0) {
            log.warn("Marked {} interrupted export job(s) as FAILED", recovered);
        }
    }
}
