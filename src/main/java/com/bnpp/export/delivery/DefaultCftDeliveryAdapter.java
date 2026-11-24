package com.bnpp.export.delivery;

import com.bnpp.export.dto.ExportFile;
import com.bnpp.export.entity.SubscriberProfile;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** Delivers a file by POSTing it to the subscriber's endpoint (export.delivery.mock=false). */
@Component
@ConditionalOnProperty(name = "export.delivery.mock", havingValue = "false")
public class DefaultCftDeliveryAdapter implements CftDeliveryAdapter {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Duration timeout;

    public DefaultCftDeliveryAdapter(@Value("${export.delivery.timeout-minutes:60}") int timeoutMinutes) {
        this.timeout = Duration.ofMinutes(Math.max(1, timeoutMinutes));
    }

    @Override
    public String deliver(SubscriberProfile subscriber, ExportFile file) {
        if (subscriber.getCftEndpoint() == null || StringUtils.isBlank(subscriber.getCftEndpoint())) {
            throw new IllegalStateException("CFT endpoint is not configured for " + subscriber.getName());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(subscriber.getCftEndpoint()))
                    .timeout(timeout)
                    .header("Content-Type", "application/octet-stream")
                    .header("X-File-Name", file.getFileName())
                    .header("X-File-Hash", file.getFileHash())
                    .header("X-Subscriber", subscriber.getName())
                    .POST(HttpRequest.BodyPublishers.ofFile(Path.of(file.getFilePath())))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("CFT delivery failed with HTTP " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return "CFT-" + UUID.randomUUID();
            }
            String body = response.body().trim();
            return body.length() > 120 ? body.substring(0, 120) : body;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CFT delivery was interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("CFT delivery failed: " + ex.getMessage(), ex);
        }
    }
}
