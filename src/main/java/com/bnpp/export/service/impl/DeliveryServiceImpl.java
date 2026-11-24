package com.bnpp.export.service.impl;

import com.bnpp.export.delivery.CftDeliveryAdapter;
import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.DeliveryRecord;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.service.DeliveryService;
import com.bnpp.export.service.StorageService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Implementation of {@link DeliveryService}. */
@Service
public class DeliveryServiceImpl implements DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final StorageService storageService;
    private final CftDeliveryAdapter adapter;

    public DeliveryServiceImpl(DeliveryRepository deliveryRepository,
                              StorageService storageService,
                              CftDeliveryAdapter adapter) {
        this.deliveryRepository = deliveryRepository;
        this.storageService = storageService;
        this.adapter = adapter;
    }

    @Override
    public DeliveryRecord deliver(SubscriberProfile subscriber, ExportFile file, Long dataVersionId, int recordCount) {
        DeliveryRecord record = new DeliveryRecord();
        record.setDataVersionId(dataVersionId);
        record.setSubscriberId(subscriber.getId());
        record.setFilePath(file.getFilePath());
        record.setFileHash(file.getFileHash());
        record.setRecordCount(recordCount);
        record.setStatus("PENDING");
        try {
            String trackingId = dispatch(subscriber, file);
            record.setArchivePath(storageService.archive(file.getFilePath(), file.getFileName()));
            record.setStatus("SUCCESS");
            record.setCftTrackingId(trackingId);
            record.setDeliveredAt(LocalDateTime.now());
        } catch (RuntimeException ex) {
            record.setStatus("FAILED");
            record.setErrorMessage(trim(ex.getMessage()));
        }
        return deliveryRepository.save(record);
    }

    private String dispatch(SubscriberProfile subscriber, ExportFile file) {
        return adapter.deliver(subscriber, file);
    }

    private String trim(String message) {
        if (message == null) {
            return "Delivery failed";
        }
        return message.length() > 3900 ? message.substring(0, 3900) : message;
    }
}
