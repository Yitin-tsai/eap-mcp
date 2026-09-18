package com.eap.mcp.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Load-test-only pause that makes the Rabbit confirm/local audit ambiguity window
 * deterministic enough for an external harness to terminate this JVM.
 */
@Component
@Profile("loadtest")
@ConditionalOnProperty(
        name = "eap.recovery-control.broker-dlq.failure-injection.post-confirm-pause-enabled",
        havingValue = "true")
@Slf4j
public class LoadTestBrokerReplayPostConfirmPause implements BrokerReplayPostConfirmProbe {

    private static final long MAX_PAUSE_MS = TimeUnit.MINUTES.toMillis(5);

    private final long pauseMs;
    private final Path markerPath;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean fired = new AtomicBoolean();

    public LoadTestBrokerReplayPostConfirmPause(
            @Value("${eap.recovery-control.broker-dlq.failure-injection.post-confirm-pause-ms:60000}")
            long pauseMs,
            @Value("${eap.recovery-control.broker-dlq.failure-injection.marker-path:}")
            String markerPath,
            ObjectMapper objectMapper) {
        if (pauseMs <= 0 || pauseMs > MAX_PAUSE_MS) {
            throw new IllegalArgumentException(
                    "broker replay post-confirm pause must be between 1 and "
                            + MAX_PAUSE_MS + " ms");
        }
        if (markerPath == null || markerPath.isBlank()) {
            throw new IllegalArgumentException(
                    "broker replay post-confirm failure-injection marker-path is required");
        }
        this.pauseMs = pauseMs;
        this.markerPath = Path.of(markerPath).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterBrokerConfirmationBeforeAudit(
            UUID actionId,
            String caseId,
            String ownerQueue) {
        if (!fired.compareAndSet(false, true)) {
            return;
        }
        if (actionId == null || caseId == null || caseId.isBlank()
                || ownerQueue == null || ownerQueue.isBlank()) {
            throw new IllegalArgumentException(
                    "actionId, caseId, and ownerQueue are required for the crash marker");
        }
        writeMarker(actionId, caseId, ownerQueue);
        log.warn(
                "REL-107 failure injection armed after replay confirm and before central audit: "
                        + "actionId={}, caseId={}, ownerQueue={}, marker={}, pauseMs={}",
                actionId, caseId, ownerQueue, markerPath, pauseMs);
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "broker replay post-confirm failure-injection pause interrupted", interrupted);
        }
    }

    private void writeMarker(UUID actionId, String caseId, String ownerQueue) {
        Path parent = markerPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "broker replay failure-injection marker-path must have a parent directory");
        }
        Path temporary = markerPath.resolveSibling(markerPath.getFileName() + ".tmp");
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("processId", ProcessHandle.current().pid());
        marker.put("actionId", actionId.toString());
        marker.put("caseId", caseId);
        marker.put("ownerQueue", ownerQueue);
        marker.put("observedAt", Instant.now().toString());
        try {
            Files.createDirectories(parent);
            objectMapper.writeValue(temporary.toFile(), marker);
            try {
                Files.move(temporary, markerPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, markerPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "could not write broker replay failure-injection marker " + markerPath,
                    failure);
        }
    }
}
