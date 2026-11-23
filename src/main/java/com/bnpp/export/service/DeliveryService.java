package com.bnpp.export.service;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.SubscriberProfile;

/** DeliveryService. */
public interface DeliveryService {

    DeliveryRecord deliver(SubscriberProfile subscriber,
                           ExportFile file,
                           Long versionId,
                           String exportMode,
                           int recordCount,
                           Long templateId,
                           Integer templateVersion,
                           String quarantineNote);
}
