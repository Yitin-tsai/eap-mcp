package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryFailureClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BrokerDeadLetterReplayPublisherTest {

    @Test
    void confirmedReplayShouldTargetOnlyOwnerQueueAndStripDeathHeaders() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        BrokerDeadLetterReplayPublisher publisher = new BrokerDeadLetterReplayPublisher(
                rabbit, new ObjectMapper(), 1_000);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(""), eq("wallet.tradeExecuted.queue"), any(Message.class), any());
        UUID actionId = UUID.randomUUID();

        publisher.publish(detail(), route(), actionId);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(""), eq("wallet.tradeExecuted.queue"), message.capture(), any());
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"tradeId\":\"trade-1\"}");
        assertThat(message.getValue().getMessageProperties().getHeaders())
                .doesNotContainKey("x-death")
                .containsEntry("traceparent", "00-trace")
                .containsEntry("x-eap-recovery-action-id", actionId.toString());
    }

    @Test
    void brokerNackShouldKeepRecoveryActionFailed() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        BrokerDeadLetterReplayPublisher publisher = new BrokerDeadLetterReplayPublisher(
                rabbit, new ObjectMapper(), 1_000);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "queue unavailable"));
            return null;
        }).when(rabbit).send(eq(""), eq("wallet.tradeExecuted.queue"), any(Message.class), any());

        assertThatThrownBy(() -> publisher.publish(detail(), route(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nacked");
    }

    private BrokerReplayRouteRegistry.ReplayRoute route() {
        return new BrokerReplayRouteRegistry.ReplayRoute(
                "wallet.tradeExecuted.queue", "trade.exchange", "trade.executed",
                "eap-wallet", "TradeExecutedEvent");
    }

    private RecoveryCaseDetail detail() {
        Instant now = Instant.parse("2026-09-18T01:00:00Z");
        RecoveryCaseSummary summary = new RecoveryCaseSummary(
                "case-1", "rabbitmq", RecoveryDebtType.BROKER_DEAD_LETTER,
                "order.dlq", "message-1", "CAPTURED", RecoveryFailureClass.TRANSIENT,
                3, now, now, "TIMEOUT", "timeout",
                Map.of("messageId", "message-1"),
                EnumSet.allOf(RecoveryActionType.class), "fingerprint");
        String payload = "{\"tradeId\":\"trade-1\"}";
        return new RecoveryCaseDetail(summary, payload, Map.of(
                "contentType", "application/json",
                "payloadBase64", Base64.getEncoder().encodeToString(
                        payload.getBytes(StandardCharsets.UTF_8)),
                "headersJson", "{\"x-death\":[],\"traceparent\":\"00-trace\"}"));
    }
}
