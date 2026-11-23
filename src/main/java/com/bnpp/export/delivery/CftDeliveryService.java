package com.bnpp.export.delivery;

import com.bnpp.export.model.DeliveryRecord;
import com.bnpp.export.model.ExportFile;
import com.bnpp.export.model.SubscriberProfile;
import com.bnpp.export.repository.DeliveryRepository;
import com.bnpp.export.service.StorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CftDeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final StorageService storageService;
    private final ObjectProvider<MockCftDeliveryAdapter> mockAdapter;
    private final ObjectProvider<DefaultCftDeliveryAdapter> defaultAdapter;

    public CftDeliveryService(DeliveryRepository deliveryRepository,
                              StorageService storageService,
                              ObjectProvider<MockCftDeliveryAdapter> mockAdapter,
                              ObjectProvider<DefaultCftDeliveryAdapter> defaultAdapter) {
        this.deliveryRepository = deliveryRepository;
        this.storageService = storageService;
        this.mockAdapter = mockAdapter;
        this.defaultAdapter = defaultAdapter;
    }

    public DeliveryRecord deliver(SubscriberProfile subscriber,
                                  ExportFile file,
                                  Long versionId,
                                  String exportMode,
                                  int recordCount,
                                  Long templateId,
                                  Integer templateVersion,
                                  String quarantineNote) {
        DeliveryRecord record = new DeliveryRecord();
        record.setVersionId(versionId);
        record.setSubscriberId(subscriber.getId());
        String fileFormat = file.getFormatType();
        record.setFormatType(fileFormat == null || fileFormat.isBlank() ? subscriber.getFormatType() : fileFormat);
        record.setExportType(exportMode);
        record.setFilePath(file.getFilePath());
        record.setFileHash(file.getFileHash());
        record.setRecordCount(recordCount);
        record.setFormatTemplateId(templateId);
        record.setFormatTemplateVersion(templateVersion);
        record.setQuarantineNote(quarantineNote);
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
        MockCftDeliveryAdapter mock = mockAdapter.getIfAvailable();
        if (mock != null) {
            return mock.deliver(subscriber, file);
        }
        return defaultAdapter.getObject().deliver(subscriber, file);
    }

    private String trim(String message) {
        if (message == null) {
            return "Delivery failed";
        }
        return message.length() > 3900 ? message.substring(0, 3900) : message;
    }
}
