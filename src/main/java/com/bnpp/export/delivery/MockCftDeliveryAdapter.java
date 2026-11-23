package com.bnpp.export.delivery;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.SubscriberProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "export.delivery.mock", havingValue = "true", matchIfMissing = true)
public class MockCftDeliveryAdapter implements CftDeliveryAdapter {

    @Override
    public String deliver(SubscriberProfile subscriber, ExportFile file) {
        return "CFT-MOCK-" + UUID.randomUUID();
    }
}
