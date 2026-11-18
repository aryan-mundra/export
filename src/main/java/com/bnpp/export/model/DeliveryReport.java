package com.bnpp.export.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DeliveryReport {

    private Long versionId;

    private String status;

    private int totalRecords;

    private int totalSubscribers;

    private int completedSubscribers;

    private int failedSubscribers;

    private List<SubscriberDeliveryStatus> subscriberStatuses = new ArrayList<>();

    @Data
    public static class SubscriberDeliveryStatus {
        private Long subscriberId;
        private String subscriberName;
        private String formatType;
        private String status;
        private int recordsProcessed;
        private int recordsQuarantined;
        private long fileSizeBytes;
        private String filePath;
    }
}
