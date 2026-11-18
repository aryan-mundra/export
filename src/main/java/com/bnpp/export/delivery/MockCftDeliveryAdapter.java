package com.bnpp.export.delivery;

import com.bnpp.export.model.ExportFile;
import com.bnpp.export.model.SubscriberProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "export.mock.enabled", havingValue = "true")
public class MockCftDeliveryAdapter {

    public String deliver(SubscriberProfile subscriber, ExportFile file) {
        return "CFT-MOCK-" + UUID.randomUUID();
    }
}
