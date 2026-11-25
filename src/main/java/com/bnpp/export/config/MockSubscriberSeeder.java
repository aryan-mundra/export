package com.bnpp.export.config;

import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.repository.SubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Six demo subscribers, so the Subscribers and Export screens have something to show. Only when {@code export.demo-data} is
 * true and the table is empty. They have no list yet: pick one under Subscribers → Edit, once a list is approved.
 * The module writes XML, so that is every demo subscriber's format.
 */
@Component
@Order(50)
@ConditionalOnProperty(name = "export.demo-data", havingValue = "true")
public class MockSubscriberSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(MockSubscriberSeeder.class);

    /** name, scope, mode, business line, region */
    private static final String[][] SEED = {
            {"SUN", "ALL", "FULL", "CIB", "EMEA"},
            {"SHINE", "SANCTIONS", "DELTA", "Retail", "APAC"},
            {"Vigilance", "WORLDCHECK", "FULL", "Compliance", "Europe"},
            {"Actimize", "PFA", "FULL", "Financial Security", "Americas"},
            {"NetReveal", "SANCTIONS", "FULL", "Financial Security", "EMEA"},
            {"Compliance Team", "ALL", "FULL", "Compliance", "Global"}
    };

    private final SubscriberRepository subscribers;

    public MockSubscriberSeeder(SubscriberRepository subscribers) {
        this.subscribers = subscribers;
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (subscribers.count() > 0) {
            log.info("There are already subscribers; the demo subscribers are not added");
            return;
        }
        for (String[] row : SEED) {
            SubscriberProfile subscriber = new SubscriberProfile();
            subscriber.setName(row[0]);
            subscriber.setFormatType("XML");
            subscriber.setScope(row[1]);
            subscriber.setExportMode(row[2]);
            subscriber.setBusinessLine(row[3]);
            subscriber.setRegion(row[4]);
            subscriber.setCftEndpoint("http://cft.local/inbox/" + row[0].toLowerCase(Locale.ROOT).replace(' ', '-'));
            subscriber.setEndpointStatus("APPROVED");
            subscriber.setActive(true);
            subscribers.save(subscriber);
        }
        log.info("Added {} demo subscribers", SEED.length);
    }
}
