package com.bnpp.export.service;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.SubscriberProfile;

/** Delivers a file to a subscriber's endpoint (CFT), archives it and records the delivery with the file's hash. */
public interface DeliveryService {

    DeliveryRecord deliver(SubscriberProfile subscriber, ExportFile file, Long dataVersionId, int recordCount);
}
