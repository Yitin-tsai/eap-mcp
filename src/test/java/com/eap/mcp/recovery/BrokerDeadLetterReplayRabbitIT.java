package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryFailureClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static com.eap.common.constants.RabbitMQConstants.TRADE_EXCHANGE;
import static com.eap.common.constants.RabbitMQConstants.TRADE_EXECUTED_KEY;
import static com.eap.common.constants.RabbitMQConstants.ORDER_TRADE_EXECUTED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "eap.integration.rabbit", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.mcp.server.enabled=false",
                "spring.liquibase.enabled=false",
                "eap.recovery-control.enabled=false",
                "eap.recovery-control.broker-dlq.enabled=false"
        })
class BrokerDeadLetterReplayRabbitIT {

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>(DockerImageName.parse(
            "rabbitmq@sha256:606d8c0d6b3c18d1da9afc53bc7cdb2a8d5486df91b5a9830e9e07626c9ae281"))
            .withEnv("RABBITMQ_DEFAULT_USER", "admin")
            .withEnv("RABBITMQ_DEFAULT_PASS", "admin123")
            .withExposedPorts(5672)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "admin");
        registry.add("spring.rabbitmq.password", () -> "admin123");
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
        registry.add("spring.rabbitmq.template.mandatory", () -> "true");
    }

    @Autowired BrokerDeadLetterReplayPublisher publisher;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void declareQueue() {
        admin.declareQueue(new Queue(WALLET_TRADE_EXECUTED_QUEUE, false, false, true));
        admin.declareQueue(new Queue(ORDER_TRADE_EXECUTED_QUEUE, false, false, true));
        rabbitTemplate.setReceiveTimeout(2_000);
    }

    @AfterEach
    void deleteQueue() {
        admin.deleteQueue(WALLET_TRADE_EXECUTED_QUEUE);
        admin.deleteQueue(ORDER_TRADE_EXECUTED_QUEUE);
    }

    @Test
    void confirmedDirectReplayShouldReachOnlyTheOwnerQueueWithRecoveryAuditHeaders() {
        UUID actionId = UUID.randomUUID();

        publisher.publish(detail(), new BrokerReplayRouteRegistry.ReplayRoute(
                WALLET_TRADE_EXECUTED_QUEUE,
                TRADE_EXCHANGE,
                TRADE_EXECUTED_KEY,
                "eap-wallet",
                "TradeExecutedEvent"), actionId);

        Message replayed = rabbitTemplate.receive(WALLET_TRADE_EXECUTED_QUEUE);
        assertThat(replayed).isNotNull();
        assertThat(new String(replayed.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"tradeId\":\"trade-live-1\"}");
        assertThat(replayed.getMessageProperties().getHeaders())
                .doesNotContainKey("x-death")
                .containsEntry("x-eap-recovery-action-id", actionId.toString())
                .containsEntry("x-eap-recovery-redrive", true);
    }

    @Test
    void confirmedOrderReplayShouldReachOrderQueueAndNotWalletQueue() {
        UUID actionId = UUID.randomUUID();

        publisher.publish(detail(), new BrokerReplayRouteRegistry.ReplayRoute(
                ORDER_TRADE_EXECUTED_QUEUE,
                TRADE_EXCHANGE,
                TRADE_EXECUTED_KEY,
                "eap-order",
                "TradeExecutedEvent"), actionId);

        assertThat(rabbitTemplate.receive(ORDER_TRADE_EXECUTED_QUEUE)).isNotNull();
        assertThat(rabbitTemplate.receive(WALLET_TRADE_EXECUTED_QUEUE)).isNull();
    }

    private RecoveryCaseDetail detail() {
        String payload = "{\"tradeId\":\"trade-live-1\"}";
        Instant now = Instant.parse("2026-09-18T01:00:00Z");
        RecoveryCaseSummary summary = new RecoveryCaseSummary(
                "case-live-1", "rabbitmq", RecoveryDebtType.BROKER_DEAD_LETTER,
                "order.dlq", "wallet.tradeExecuted.queue:message-live-1", "CAPTURED",
                RecoveryFailureClass.TRANSIENT, 3, now, now,
                "BROKER_DEAD_LETTER", "database timeout",
                Map.of("messageId", "message-live-1"),
                EnumSet.allOf(RecoveryActionType.class), "fingerprint-live-1");
        return new RecoveryCaseDetail(summary, payload, Map.of(
                "contentType", "application/json",
                "payloadBase64", Base64.getEncoder().encodeToString(
                        payload.getBytes(StandardCharsets.UTF_8)),
                "headersJson", "{\"x-death\":[],\"traceparent\":\"00-live-trace\"}"));
    }
}
