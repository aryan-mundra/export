package com.bnpp.export.service.impl;

import com.bnpp.export.entity.CertifiedVersion;
import com.bnpp.export.entity.ExportJob;
import com.bnpp.export.entity.ExportJobSubscriberStatus;
import com.bnpp.export.entity.SubscriberProfile;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.format.ExportFormat;
import com.bnpp.export.repository.CertifiedVersionRepository;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.repository.ExportJobSubscriberStatusRepository;
import com.bnpp.export.repository.SubscriberRepository;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.QualityCheckService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What the export services share: finding a version, job, subscriber file or subscriber (with the module's error
 * messages), the file formats the module writes, the audit log, and a few small helpers.
 */
@Component
class ExportSupport {

    /** Batch size for reading, quarantining and progress updates while a file streams. */
    static final int STREAM_BATCH = 500;

    private final SubscriberRepository subscriberRepository;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobSubscriberStatusRepository statusRepository;
    private final CertifiedVersionRepository certifiedVersionRepository;
    private final AuditService auditService;
    private final QualityCheckService qualityChecker;
    private final Map<String, ExportFormat> formats;

    ExportSupport(SubscriberRepository subscriberRepository,
                  ExportJobRepository exportJobRepository,
                  ExportJobSubscriberStatusRepository statusRepository,
                  CertifiedVersionRepository certifiedVersionRepository,
                  AuditService auditService,
                  QualityCheckService qualityChecker,
                  List<ExportFormat> formatList) {
        this.subscriberRepository = subscriberRepository;
        this.exportJobRepository = exportJobRepository;
        this.statusRepository = statusRepository;
        this.certifiedVersionRepository = certifiedVersionRepository;
        this.auditService = auditService;
        this.qualityChecker = qualityChecker;
        this.formats = formatList.stream()
                .collect(Collectors.toMap(format -> format.formatType().toUpperCase(Locale.ROOT), format -> format));
    }

    // ------------------------------------------------------------------ formats

    /** The file formats the module can write, in alphabetical order. */
    List<String> formatTypes() {
        return formats.keySet().stream().sorted().toList();
    }

    /** The writer of a format, or null when the module has none. */
    ExportFormat format(String formatType) {
        return formatType == null ? null : formats.get(formatType.toUpperCase(Locale.ROOT));
    }

    /** One of the module's formats, or null for each subscriber's own format. */
    String sendFormat(String formatType) {
        if (StringUtils.isBlank(formatType)) {
            return null;
        }
        String format = formatType.trim().toUpperCase(Locale.ROOT);
        if (!formats.containsKey(format)) {
            throw new BadRequestException("Format must be one of " + String.join(", ", formats.keySet()));
        }
        return format;
    }

    /** One or more formats written as {@code CSV} or {@code CSV,XML}. Blank means none were chosen. */
    List<String> sendFormats(String value) {
        if (StringUtils.isBlank(value)) {
            return List.of();
        }
        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String format = sendFormat(part);
            if (format != null) {
                chosen.add(format);
            }
        }
        return List.copyOf(chosen);
    }

    /** The formats saved for a subscriber, one or more, such as {@code CSV} or {@code CSV,XML}. */
    List<String> ownFormats(SubscriberProfile subscriber) {
        List<String> listed = sendFormats(subscriber.getFormatType());
        if (listed.isEmpty()) {
            throw new BadRequestException(subscriber.getName() + " has no format");
        }
        return listed;
    }

    // ------------------------------------------------------------------ lookups

    /**
     * Every active subscriber when none are given; otherwise the chosen ones, which must exist and be
     * active. Order follows the subscriber ids, so reviews and runs list them the same way.
     */
    List<SubscriberProfile> chosenSubscribers(List<Long> subscriberIds) {
        if (subscriberIds == null || subscriberIds.isEmpty()) {
            return subscriberRepository.findByActiveTrueOrderByIdAsc();
        }
        List<SubscriberProfile> chosen = new ArrayList<>();
        for (Long id : new TreeSet<>(subscriberIds)) {
            SubscriberProfile subscriber = requireSubscriber(id);
            if (!subscriber.isActive()) {
                throw new BadRequestException(subscriber.getName() + " is not active");
            }
            chosen.add(subscriber);
        }
        return chosen;
    }

    CertifiedVersion requireVersion(Long versionId) {
        return certifiedVersionRepository.findByVersionId(versionId)
                .filter(version -> "CERTIFIED".equals(version.getStatus()))
                .orElseThrow(() -> new BadRequestException("Choose a certified list version. This catalog is the demo stand-in until the golden source is connected."));
    }

    ExportJob requireJob(Long jobId) {
        return exportJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Export job not found"));
    }

    ExportJobSubscriberStatus requireStatus(Long jobId, Long statusId) {
        ExportJobSubscriberStatus status = statusRepository.findById(statusId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscriber file not found"));
        if (!jobId.equals(status.getExportJobId())) {
            throw new ResourceNotFoundException("Subscriber file not found");
        }
        return status;
    }

    SubscriberProfile requireSubscriber(Long subscriberId) {
        return subscriberRepository.findById(subscriberId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscriber not found"));
    }

    // ------------------------------------------------------------------ helpers

    void audit(Long jobId, Long subscriberId, String action, String details) {
        auditService.job(jobId, subscriberId, action, details);
    }

    /** The list entry fields that must not be blank; an entry with a blank one is quarantined. */
    List<String> decisionColumns() {
        return qualityChecker.columnsForRun(List.of());
    }

    /**
     * Whether a subscriber receives a list. Every subscriber now receives every list (its scope filters the rows
     * inside), so this is always true; it is kept as the one place to add such a rule back.
     */
    static boolean onList(SubscriberProfile subscriber, String listCode) {
        return true;
    }

    static void write(ExportFormat.RecordWriter writer, Map<String, Object> record) {
        try {
            writer.write(record);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the export file: " + ex.getMessage(), ex);
        }
    }

    static String noTemplate(SubscriberProfile subscriber) {
        return subscriber.getTemplateFamilyId() == null
                ? subscriber.getName() + " has no list. Choose one on the Subscribers page."
                : "The list of " + subscriber.getName() + " has no approved version yet.";
    }

    /** A readable reason, also for errors that carry no message. */
    static String reason(Throwable ex) {
        String message = ex.getMessage();
        return ex instanceof Error
                ? "Internal error (" + ex.getClass().getSimpleName() + (message == null ? "" : ": " + message) + ")"
                : StringUtils.defaultIfBlank(message, ex.getClass().getSimpleName());
    }

    /** Error messages are stored in a 4000-character column. */
    static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 3900 ? value.substring(0, 3900) : value;
    }

    static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    /** Lower-case hex SHA-256 of the text: review tokens. */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
