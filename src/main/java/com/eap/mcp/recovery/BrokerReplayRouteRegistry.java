package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryCaseDetail;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.eap.common.constants.RabbitMQConstants.ORDER_TRADE_EXECUTED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.TRADE_EXCHANGE;
import static com.eap.common.constants.RabbitMQConstants.TRADE_EXECUTED_KEY;
import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;

@Component
public class BrokerReplayRouteRegistry {

    private static final List<ReplayRoute> ROUTES = List.of(
            new ReplayRoute(
                    WALLET_TRADE_EXECUTED_QUEUE,
                    TRADE_EXCHANGE,
                    TRADE_EXECUTED_KEY,
                    "eap-wallet",
                    "TradeExecutedEvent"),
            new ReplayRoute(
                    ORDER_TRADE_EXECUTED_QUEUE,
                    TRADE_EXCHANGE,
                    TRADE_EXECUTED_KEY,
                    "eap-order",
                    "TradeExecutedEvent"));

    public Optional<ReplayRoute> resolve(RecoveryCaseDetail detail) {
        String sourceQueue = detail.summary().identity().get("sourceQueue");
        String exchange = detail.summary().identity().get("originalExchange");
        String routingKey = detail.summary().identity().get("originalRoutingKey");
        return resolve(sourceQueue, exchange, routingKey);
    }

    public Optional<ReplayRoute> resolve(String sourceQueue, String exchange, String routingKey) {
        return ROUTES.stream()
                .filter(route -> route.matches(sourceQueue, exchange, routingKey))
                .findFirst();
    }

    public record ReplayRoute(
            String queue,
            String originalExchange,
            String originalRoutingKey,
            String ownerService,
            String work) {

        boolean matches(String queue, String exchange, String routingKey) {
            return this.queue.equals(queue)
                    && this.originalExchange.equals(exchange)
                    && this.originalRoutingKey.equals(routingKey);
        }
    }
}
