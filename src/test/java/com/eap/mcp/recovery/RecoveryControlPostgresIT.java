package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseId;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryExecuteResult;
import com.eap.common.recovery.RecoveryExecutionStatus;
import com.eap.common.recovery.RecoveryFailureClass;
import com.eap.mcp.recovery.api.RecoveryControlActionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@EnabledIfSystemProperty(named = "eap.integration.postgres", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.mcp.server.enabled=false",
                "spring.liquibase.enabled=true",
                "eap.recovery-control.enabled=false",
                "eap.recovery-control.broker-dlq.enabled=false"
        })
class RecoveryControlPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.6"));

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RecoveryAuditStore audit;
    @Autowired BrokerDeadLetterStore broker;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM recovery_control.recovery_actions");
        jdbc.update("DELETE FROM recovery_control.recovery_cases");
        jdbc.update("DELETE FROM recovery_control.broker_dead_letters");
    }

    @Test
    void actionShouldBeRateLimitedAuditedAndIdempotentlyReadable() {
        RecoveryCaseSummary before = summary();
        audit.observe(before);
        UUID actionId = UUID.randomUUID();
        RecoveryControlActionRequest request = new RecoveryControlActionRequest(
                actionId, RecoveryActionType.REPLAY, before.fingerprint(), "database recovered");

        assertThat(audit.begin(before.caseId(), request, "operator-a", before).completed()).isFalse();
        RecoveryExecuteResult result = new RecoveryExecuteResult(
                actionId, before.caseId(), RecoveryActionType.REPLAY,
                RecoveryExecutionStatus.APPLIED, "requeued", before, null);
        audit.complete(result);

        assertThat(audit.completed(before.caseId(), request, "operator-a")).isEqualTo(result);
        assertThat(audit.action(actionId).attemptCount()).isEqualTo(1);
        assertThat(audit.action(actionId).reason()).isEqualTo("database recovered");
        assertThatThrownBy(() -> audit.completed(
                before.caseId(),
                new RecoveryControlActionRequest(
                        actionId, RecoveryActionType.REPLAY, before.fingerprint(), "different reason"),
                "operator-a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void brokerCaptureShouldPersistPayloadAndFailClosedOnReplay() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("dead-letter-1");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("x-exception-message", "database connection timeout");
        properties.setHeader("x-death", List.of(Map.of(
                "queue", "wallet.orderSubmitted.queue",
                "exchange", "order.exchange",
                "routing-keys", List.of("order.submitted"),
                "count", 3L,
                "time", Date.from(Instant.parse("2026-09-17T01:00:00Z")))));
        Message message = new Message("{\"orderId\":\"o-1\"}".getBytes(StandardCharsets.UTF_8), properties);

        var captured = broker.capture(message);
        var loaded = broker.detail(captured.summary().caseId());

        assertThat(loaded.payload()).contains("orderId");
        assertThat(loaded.summary().failureClass()).isEqualTo(RecoveryFailureClass.TRANSIENT);
        assertThat(loaded.summary().attemptCount()).isEqualTo(3);
        assertThat(loaded.summary().identity())
                .containsEntry("sourceQueue", "wallet.orderSubmitted.queue")
                .containsEntry("originalRoutingKey", "order.submitted");
        assertThat(loaded.summary().allowedActions())
                .containsExactlyInAnyOrder(RecoveryActionType.PARK, RecoveryActionType.RESOLVE);
    }

    @Test
    void newActionIdsShouldBeBoundedPerOperatorMinute() {
        RecoveryCaseSummary before = summary();
        for (int index = 0; index < 10; index++) {
            RecoveryControlActionRequest request = new RecoveryControlActionRequest(
                    UUID.randomUUID(), RecoveryActionType.PARK, before.fingerprint(), "investigate-" + index);
            assertThat(audit.begin(before.caseId(), request, "operator-limited", before).completed()).isFalse();
        }
        RecoveryControlActionRequest rejected = new RecoveryControlActionRequest(
                UUID.randomUUID(), RecoveryActionType.PARK, before.fingerprint(), "one too many");

        assertThatThrownBy(() -> audit.begin(before.caseId(), rejected, "operator-limited", before))
                .isInstanceOf(RecoveryRateLimitExceededException.class);
    }

    private RecoveryCaseSummary summary() {
        String caseId = RecoveryCaseId.encode(
                "eap-order", RecoveryDebtType.OUTBOX_TERMINAL, "event_outbox", "17");
        Instant now = Instant.parse("2026-09-17T01:00:00Z");
        return new RecoveryCaseSummary(
                caseId, "eap-order", RecoveryDebtType.OUTBOX_TERMINAL,
                "event_outbox", "17", "FAILED", RecoveryFailureClass.TRANSIENT,
                10, now, now, "PUBLISH_RETRY_EXHAUSTED", "broker unavailable",
                Map.of("outboxId", "17"), EnumSet.allOf(RecoveryActionType.class), "fingerprint-17");
    }
}
