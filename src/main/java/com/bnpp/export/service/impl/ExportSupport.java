package com.bnpp.export.service.impl;

import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.SubscriberRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** What the send, run and job services share: finding a send, a subscriber's file or a subscriber, and small helpers. */
@Component
class ExportSupport {

    private final ExportJobRepository jobs;
    private final ExportJobSubscriberStatusRepository files;
    private final SubscriberRepository subscribers;

    ExportSupport(ExportJobRepository jobs, ExportJobSubscriberStatusRepository files, SubscriberRepository subscribers) {
        this.jobs = jobs;
        this.files = files;
        this.subscribers = subscribers;
    }

    ExportJob requireJob(Long jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Export job not found"));
    }

    /** A subscriber's file of a send: it must belong to that send. */
    ExportJobSubscriberStatus requireFile(Long jobId, Long fileId) {
        ExportJobSubscriberStatus file = files.findById(fileId).orElseThrow(() -> new ResourceNotFoundException("Subscriber file not found"));
        if (!jobId.equals(file.getExportJobId())) {
            throw new ResourceNotFoundException("Subscriber file not found");
        }
        return file;
    }

    SubscriberProfile requireSubscriber(Long subscriberId) {
        return subscribers.findById(subscriberId).orElseThrow(() -> new ResourceNotFoundException("Subscriber not found"));
    }

    /** A readable reason, also for errors that carry no message. */
    static String reason(Throwable ex) {
        String message = ex.getMessage();
        return ex instanceof Error
                ? "Internal error (" + ex.getClass().getSimpleName() + (message == null ? "" : ": " + message) + ")"
                : StringUtils.defaultIfBlank(message, ex.getClass().getSimpleName());
    }

    /** Error messages are stored in 4000-character columns. */
    static String trim(String value) {
        return value == null || value.length() <= 3900 ? value : value.substring(0, 3900);
    }

    static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
