package com.eap.mcp.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadTestBrokerReplayPostConfirmPauseTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesMachineReadableMarkerAndFiresOnlyOnce() throws Exception {
        Path marker = temporaryDirectory.resolve("broker-replay-post-confirm.json");
        UUID firstAction = UUID.randomUUID();
        LoadTestBrokerReplayPostConfirmPause probe =
                new LoadTestBrokerReplayPostConfirmPause(1, marker.toString(), objectMapper);

        probe.afterBrokerConfirmationBeforeAudit(firstAction, "case-1", "owner.queue");
        JsonNode first = objectMapper.readTree(marker.toFile());
        probe.afterBrokerConfirmationBeforeAudit(UUID.randomUUID(), "case-2", "other.queue");
        JsonNode second = objectMapper.readTree(marker.toFile());

        assertThat(first.path("processId").asLong()).isPositive();
        assertThat(first.path("actionId").asText()).isEqualTo(firstAction.toString());
        assertThat(first.path("caseId").asText()).isEqualTo("case-1");
        assertThat(first.path("ownerQueue").asText()).isEqualTo("owner.queue");
        assertThat(second).isEqualTo(first);
        assertThat(Files.exists(marker.resolveSibling(
                "broker-replay-post-confirm.json.tmp"))).isFalse();
    }

    @Test
    void rejectsUnsafeConfigurationAndMissingMarkerIdentity() {
        assertThatThrownBy(() -> new LoadTestBrokerReplayPostConfirmPause(
                0, "marker.json", objectMapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoadTestBrokerReplayPostConfirmPause(
                1, " ", objectMapper))
                .isInstanceOf(IllegalArgumentException.class);

        LoadTestBrokerReplayPostConfirmPause probe = new LoadTestBrokerReplayPostConfirmPause(
                1, temporaryDirectory.resolve("marker.json").toString(), objectMapper);
        assertThatThrownBy(() -> probe.afterBrokerConfirmationBeforeAudit(
                null, "case-1", "owner.queue"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
