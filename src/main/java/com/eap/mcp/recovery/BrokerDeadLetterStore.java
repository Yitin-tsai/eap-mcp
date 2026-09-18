package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseId;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryFailureClass;
import com.eap.common.recovery.RecoveryFingerprint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

@Component
public class BrokerDeadLetterStore {

    public static final String SERVICE = "rabbitmq";
    public static final String WORK = "order.dlq";

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BrokerReplayRouteRegistry replayRoutes;

    public BrokerDeadLetterStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            BrokerReplayRouteRegistry replayRoutes) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.replayRoutes = replayRoutes;
    }

    @Transactional
    public RecoveryCaseDetail capture(Message message) {
        byte[] body = message.getBody();
        String payload = new String(body, StandardCharsets.UTF_8);
        String payloadBase64 = Base64.getEncoder().encodeToString(body);
        Map<String, Object> headers = normalizeHeaders(message.getMessageProperties().getHeaders());
        Death death = death(headers);
        String messageId = message.getMessageProperties().getMessageId();
        String payloadHash = RecoveryFingerprint.sha256(payloadBase64);
        String messageIdentity = messageId == null || messageId.isBlank()
                ? RecoveryFingerprint.sha256(
                        payloadHash, death.queue(), death.exchange(), death.routingKey())
                : messageId;
        String sourceId = death.queue() == null || death.queue().isBlank()
                ? messageIdentity
                : death.queue() + ":" + messageIdentity;
        String caseId = RecoveryCaseId.encode(
                SERVICE, RecoveryDebtType.BROKER_DEAD_LETTER, WORK, sourceId);
        String error = firstText(headers,
                "x-exception-message", "x-first-death-reason", "x-last-death-reason");
        RecoveryFailureClass failureClass = classify(error);
        Instant firstFailed = death.firstFailedAt() == null ? Instant.now() : death.firstFailedAt();
        Instant lastFailed = death.lastFailedAt() == null ? firstFailed : death.lastFailedAt();
        int redriveCount = Math.max(0, Math.min(death.count(), Integer.MAX_VALUE));
        String headersJson = write(headers);
        String fingerprint = RecoveryFingerprint.sha256(
                caseId, payloadBase64, headersJson, Integer.toString(redriveCount), lastFailed.toString());

        jdbc.update("""
                INSERT INTO recovery_control.broker_dead_letters
                    (case_id, source_id, source_queue, original_exchange,
                     original_routing_key, message_id, content_type,
                     payload, payload_base64, headers_json, failure_class,
                     last_error, redrive_count, first_failed_at, last_failed_at,
                     fingerprint, captured_at, updated_at)
                VALUES
                    (:caseId, :sourceId, :sourceQueue, :exchange,
                     :routingKey, :messageId, :contentType,
                     :payload, :payloadBase64, :headersJson, :failureClass,
                     :lastError, :redriveCount, :firstFailedAt, :lastFailedAt,
                     :fingerprint, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (case_id) DO UPDATE
                SET source_queue = EXCLUDED.source_queue,
                    original_exchange = EXCLUDED.original_exchange,
                    original_routing_key = EXCLUDED.original_routing_key,
                    content_type = EXCLUDED.content_type,
                    payload = EXCLUDED.payload,
                    payload_base64 = EXCLUDED.payload_base64,
                    headers_json = EXCLUDED.headers_json,
                    failure_class = EXCLUDED.failure_class,
                    last_error = EXCLUDED.last_error,
                    redrive_count = GREATEST(
                        recovery_control.broker_dead_letters.redrive_count,
                        EXCLUDED.redrive_count),
                    first_failed_at = LEAST(
                        recovery_control.broker_dead_letters.first_failed_at,
                        EXCLUDED.first_failed_at),
                    last_failed_at = GREATEST(
                        recovery_control.broker_dead_letters.last_failed_at,
                        EXCLUDED.last_failed_at),
                    fingerprint = EXCLUDED.fingerprint,
                    updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource()
                .addValue("caseId", caseId)
                .addValue("sourceId", sourceId)
                .addValue("sourceQueue", death.queue())
                .addValue("exchange", death.exchange())
                .addValue("routingKey", death.routingKey())
                .addValue("messageId", messageId)
                .addValue("contentType", message.getMessageProperties().getContentType())
                .addValue("payload", payload)
                .addValue("payloadBase64", payloadBase64)
                .addValue("headersJson", headersJson)
                .addValue("failureClass", failureClass.name())
                .addValue("lastError", error)
                .addValue("redriveCount", redriveCount)
                .addValue("firstFailedAt", Timestamp.from(firstFailed))
                .addValue("lastFailedAt", Timestamp.from(lastFailed))
                .addValue("fingerprint", fingerprint));
        return detail(caseId);
    }

    @Transactional(readOnly = true)
    public List<RecoveryCaseSummary> list(int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        return query(null, limit).stream().map(RecoveryCaseDetail::summary).toList();
    }

    @Transactional(readOnly = true)
    public RecoveryCaseDetail detail(String caseId) {
        RecoveryCaseId.Parts parts = RecoveryCaseId.decode(caseId);
        if (!SERVICE.equals(parts.service())
                || parts.debtType() != RecoveryDebtType.BROKER_DEAD_LETTER
                || !WORK.equals(parts.work())) {
            throw new IllegalArgumentException("Recovery case is not a managed broker dead letter");
        }
        return query(parts.sourceId(), 1).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Broker dead letter not found: " + caseId));
    }

    private List<RecoveryCaseDetail> query(String sourceId, int limit) {
        return jdbc.query("""
                SELECT case_id, source_id, source_queue, original_exchange,
                       original_routing_key, message_id, content_type,
                       payload, payload_base64, headers_json, failure_class,
                       last_error, redrive_count, first_failed_at, last_failed_at,
                       fingerprint, updated_at
                FROM recovery_control.broker_dead_letters
                WHERE (CAST(:sourceId AS text) IS NULL OR source_id = :sourceId)
                ORDER BY first_failed_at, case_id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("limit", limit),
                (rs, rowNum) -> map(rs));
    }

    private RecoveryCaseDetail map(ResultSet rs) throws SQLException {
        Map<String, String> identity = new LinkedHashMap<>();
        put(identity, "messageId", rs.getString("message_id"));
        put(identity, "sourceQueue", rs.getString("source_queue"));
        put(identity, "originalExchange", rs.getString("original_exchange"));
        put(identity, "originalRoutingKey", rs.getString("original_routing_key"));
        put(identity, "payloadHash", RecoveryFingerprint.sha256(rs.getString("payload_base64")));
        Instant firstSeen = rs.getTimestamp("first_failed_at").toInstant();
        Instant lastUpdated = rs.getTimestamp("updated_at").toInstant();
        if (lastUpdated.isBefore(firstSeen)) {
            lastUpdated = firstSeen;
        }
        RecoveryFailureClass failureClass = RecoveryFailureClass.valueOf(
                rs.getString("failure_class"));
        EnumSet<RecoveryActionType> allowedActions = EnumSet.of(
                RecoveryActionType.PARK, RecoveryActionType.RESOLVE);
        BrokerReplayRouteRegistry.ReplayRoute replayRoute = replayRoutes.resolve(
                        rs.getString("source_queue"),
                        rs.getString("original_exchange"),
                        rs.getString("original_routing_key"))
                .orElse(null);
        boolean replaySupported = failureClass == RecoveryFailureClass.TRANSIENT
                && replayRoute != null;
        if (replaySupported) {
            allowedActions.add(RecoveryActionType.REPLAY);
        }
        RecoveryCaseSummary summary = new RecoveryCaseSummary(
                rs.getString("case_id"),
                SERVICE,
                RecoveryDebtType.BROKER_DEAD_LETTER,
                WORK,
                rs.getString("source_id"),
                "CAPTURED",
                failureClass,
                rs.getInt("redrive_count"),
                firstSeen,
                lastUpdated,
                "BROKER_DEAD_LETTER",
                rs.getString("last_error"),
                identity,
                allowedActions,
                rs.getString("fingerprint"));
        Map<String, String> metadata = new LinkedHashMap<>();
        put(metadata, "sourceQueue", rs.getString("source_queue"));
        put(metadata, "originalExchange", rs.getString("original_exchange"));
        put(metadata, "originalRoutingKey", rs.getString("original_routing_key"));
        put(metadata, "contentType", rs.getString("content_type"));
        put(metadata, "headersJson", rs.getString("headers_json"));
        put(metadata, "payloadBase64", rs.getString("payload_base64"));
        put(metadata, "lastFailedAt", rs.getTimestamp("last_failed_at").toInstant().toString());
        put(metadata, "replayPolicy", replaySupported
                ? "conditional: exact topology ownership plus owner business-state preflight"
                : "disabled: failure class or source topology is not allowlisted for safe replay");
        if (replaySupported) {
            put(metadata, "ownerService", replayRoute.ownerService());
            put(metadata, "ownerWork", replayRoute.work());
        }
        return new RecoveryCaseDetail(summary, rs.getString("payload"), metadata);
    }

    private Map<String, Object> normalizeHeaders(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, normalize(value)));
        return result;
    }

    private Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), normalize(item)));
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(item -> normalized.add(normalize(item)));
            return normalized;
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private Death death(Map<String, Object> headers) {
        Object raw = headers.get("x-death");
        if (!(raw instanceof List<?> deaths) || deaths.isEmpty()
                || !(deaths.get(0) instanceof Map<?, ?> firstRaw)) {
            return new Death(null, null, null, 0, null, null);
        }
        Map<String, Object> first = (Map<String, Object>) firstRaw;
        String routingKey = null;
        Object routes = first.get("routing-keys");
        if (routes instanceof List<?> values && !values.isEmpty()) {
            routingKey = String.valueOf(values.get(0));
        }
        int count = first.get("count") instanceof Number number ? number.intValue() : 0;
        Instant time = instant(first.get("time"));
        return new Death(
                text(first.get("queue")),
                text(first.get("exchange")),
                routingKey,
                count,
                time,
                time);
    }

    private RecoveryFailureClass classify(String error) {
        if (error == null) {
            return RecoveryFailureClass.UNKNOWN;
        }
        String normalized = error.toUpperCase(Locale.ROOT);
        if (normalized.contains("DESERIAL") || normalized.contains("SCHEMA")
                || normalized.contains("JSON") || normalized.contains("PAYLOAD")) {
            return RecoveryFailureClass.SCHEMA;
        }
        if (normalized.contains("IDENTITY") || normalized.contains("CONFLICT")) {
            return RecoveryFailureClass.IDENTITY;
        }
        if (normalized.contains("INVARIANT") || normalized.contains("OWNERSHIP")
                || normalized.contains("INSUFFICIENT")) {
            return RecoveryFailureClass.INVARIANT;
        }
        if (normalized.contains("TIMEOUT") || normalized.contains("CONNECTION")
                || normalized.contains("UNAVAILABLE") || normalized.contains("DATABASE")
                || normalized.contains("REDIS") || normalized.contains("LOCK")) {
            return RecoveryFailureClass.TRANSIENT;
        }
        return RecoveryFailureClass.UNKNOWN;
    }

    private String firstText(Map<String, Object> headers, String... keys) {
        for (String key : keys) {
            String value = text(headers.get(key));
            if (value != null && !value.isBlank()) {
                return value.length() <= 2000 ? value : value.substring(0, 2000);
            }
        }
        return null;
    }

    private String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize broker dead-letter headers", failure);
        }
    }

    private Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private void put(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private record Death(
            String queue,
            String exchange,
            String routingKey,
            int count,
            Instant firstFailedAt,
            Instant lastFailedAt) {
    }
}
