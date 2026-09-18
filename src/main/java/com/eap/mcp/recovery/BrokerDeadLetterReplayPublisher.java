package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryCaseDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class BrokerDeadLetterReplayPublisher {

    private static final TypeReference<Map<String, Object>> HEADERS = new TypeReference<>() {
    };

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmTimeoutMs;

    public BrokerDeadLetterReplayPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${eap.recovery-control.broker-dlq.replay-confirm-timeout-ms:5000}")
            long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeoutMs = Math.max(100, confirmTimeoutMs);
    }

    public void publish(
            RecoveryCaseDetail detail,
            BrokerReplayRouteRegistry.ReplayRoute route,
            UUID actionId) {
        MessageProperties properties = new MessageProperties();
        String contentType = detail.sourceMetadata().get("contentType");
        if (contentType != null && !contentType.isBlank()) {
            properties.setContentType(contentType);
        }
        String messageId = detail.summary().identity().get("messageId");
        if (messageId != null && !messageId.isBlank()) {
            properties.setMessageId(messageId);
        }
        safeHeaders(detail.sourceMetadata().get("headersJson")).forEach(properties::setHeader);
        properties.setHeader("x-eap-recovery-case-id", detail.summary().caseId());
        properties.setHeader("x-eap-recovery-action-id", actionId.toString());
        properties.setHeader("x-eap-recovery-redrive", true);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        String encodedBody = detail.sourceMetadata().get("payloadBase64");
        if (encodedBody == null || encodedBody.isBlank()) {
            throw new IllegalArgumentException("Broker recovery case has no captured payload bytes");
        }
        byte[] body = Base64.getDecoder().decode(encodedBody);
        CorrelationData correlation = new CorrelationData(actionId.toString());
        rabbitTemplate.send("", route.queue(), new Message(body, properties), correlation);
        awaitConfirm(correlation);
    }

    private void awaitConfirm(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                throw new IllegalStateException(
                        "Broker replay was returned: " + correlation.getReturned().getReplyText());
            }
            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker replay was nacked: " + confirm.getReason());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting broker replay confirm", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Broker replay confirm was not received", failure);
        }
    }

    private Map<String, Object> safeHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> original = objectMapper.readValue(headersJson, HEADERS);
            Map<String, Object> result = new LinkedHashMap<>();
            original.forEach((name, value) -> {
                String normalized = name.toLowerCase(Locale.ROOT);
                if (!normalized.startsWith("x-death")
                        && !normalized.startsWith("x-first-death")
                        && !normalized.startsWith("x-last-death")
                        && !normalized.startsWith("x-exception")
                        && !normalized.startsWith("x-eap-recovery")) {
                    result.put(name, value);
                }
            });
            return result;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot restore broker dead-letter headers", failure);
        }
    }
}
