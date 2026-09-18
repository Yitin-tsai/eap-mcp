package com.eap.mcp.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Load-test fixture publisher. It sends an expiring message through the real trade
 * route so RabbitMQ itself records the trusted {@code x-death} queue/exchange/key
 * metadata when the Wallet queue dead-letters it.
 */
public final class BrokerDeadLetterSeedPublisher {

    static final String BUYER_ID = "11111111-1111-1111-1111-111111111111";
    static final String SELLER_ID = "22222222-2222-2222-2222-222222222222";
    static final String BUYER_ORDER_ID = "33333333-3333-3333-3333-333333333333";
    static final String SELLER_ORDER_ID = "44444444-4444-4444-4444-444444444444";

    private BrokerDeadLetterSeedPublisher() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String host = options.getOrDefault("host", "localhost");
        int port = Integer.parseInt(options.getOrDefault("port", "5672"));
        String username = options.getOrDefault("username", "admin");
        String password = options.getOrDefault("password", "admin123");
        String messageId = required(options, "message-id");
        String tradeId = required(options, "trade-id");

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        try (Connection connection = factory.newConnection("rel107-broker-dead-letter-seed");
             Channel channel = connection.createChannel()) {
            channel.exchangeDeclarePassive("trade.exchange");
            channel.confirmSelect();
            channel.basicPublish(
                    "trade.exchange",
                    "trade.executed",
                    true,
                    properties(messageId),
                    payload(tradeId));
            channel.waitForConfirmsOrDie(5_000);
        }
        System.out.printf(
                "{\"messageId\":\"%s\",\"tradeId\":\"%s\",\"publishedAt\":\"%s\"}%n",
                messageId, tradeId, Instant.now());
    }

    private static AMQP.BasicProperties properties(String messageId) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x-exception-message", "database connection timeout");
        return new AMQP.BasicProperties.Builder()
                .messageId(messageId)
                .contentType("application/json")
                .deliveryMode(2)
                .expiration("1000")
                .headers(headers)
                .build();
    }

    private static byte[] payload(String tradeId) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tradeId", tradeId);
        event.put("sequence", 1L);
        event.put("legacyMatchId", 1);
        event.put("marketId", "REL107_MCP_CONFIRM_CRASH");
        event.put("buyerId", BUYER_ID);
        event.put("sellerId", SELLER_ID);
        event.put("buyerOrderId", BUYER_ORDER_ID);
        event.put("sellerOrderId", SELLER_ORDER_ID);
        event.put("buyerMarketSequence", 1L);
        event.put("sellerMarketSequence", 2L);
        event.put("originBuyerPrice", 120);
        event.put("originSellerPrice", 100);
        event.put("dealPrice", 110);
        event.put("quantity", 5);
        event.put("occurredAt", "2026-09-18T12:00:00");
        return new ObjectMapper().writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (!option.startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --name value arguments");
            }
            options.put(option.substring(2), args[++index]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }
}
