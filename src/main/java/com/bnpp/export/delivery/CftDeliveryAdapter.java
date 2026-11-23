package com.bnpp.export.delivery;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.SubscriberProfile;

/**
 * Sends a built file to a subscriber over CFT. Exactly one implementation is active, chosen by
 * {@code export.delivery.mock}: {@link MockCftDeliveryAdapter} (simulated) or {@link DefaultCftDeliveryAdapter}.
 */
public interface CftDeliveryAdapter {

    /** Sends the file and returns the CFT tracking id; throws when the send fails. */
    String deliver(SubscriberProfile subscriber, ExportFile file);
}
