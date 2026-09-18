package com.eap.mcp.recovery;

import com.eap.common.recovery.BrokerReplayPreflightDecision;
import com.eap.common.recovery.BrokerReplayPreflightRequest;
import com.eap.common.recovery.BrokerReplayPreflightResult;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryFailureClass;
import com.eap.mcp.recovery.client.OrderRecoverySourceClient;
import com.eap.mcp.recovery.client.WalletRecoverySourceClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class BrokerDeadLetterReplayCoordinator {

    private final BrokerReplayRouteRegistry routes;
    private final WalletRecoverySourceClient wallet;
    private final OrderRecoverySourceClient order;
    private final BrokerDeadLetterReplayPublisher publisher;
    private final List<BrokerReplayPostConfirmProbe> postConfirmProbes;

    public BrokerDeadLetterReplayCoordinator(
            BrokerReplayRouteRegistry routes,
            WalletRecoverySourceClient wallet,
            OrderRecoverySourceClient order,
            BrokerDeadLetterReplayPublisher publisher,
            List<BrokerReplayPostConfirmProbe> postConfirmProbes) {
        this.routes = routes;
        this.wallet = wallet;
        this.order = order;
        this.publisher = publisher;
        this.postConfirmProbes = List.copyOf(postConfirmProbes);
    }

    public BrokerReplayPreflightResult preflight(RecoveryCaseDetail detail, String sourceToken) {
        if (detail.summary().failureClass() != RecoveryFailureClass.TRANSIENT) {
            return new BrokerReplayPreflightResult(
                    BrokerReplayPreflightDecision.UNSUPPORTED_ROUTE,
                    false,
                    "Only explicitly classified transient broker failures may be replayed",
                    null,
                    null);
        }
        BrokerReplayRouteRegistry.ReplayRoute route = routes.resolve(detail).orElse(null);
        if (route == null) {
            return new BrokerReplayPreflightResult(
                    BrokerReplayPreflightDecision.UNSUPPORTED_ROUTE,
                    false,
                    "Broker source topology is not allowlisted for safe replay",
                    null,
                    null);
        }
        BrokerReplayPreflightRequest request = new BrokerReplayPreflightRequest(
                route.queue(), route.originalExchange(), route.originalRoutingKey(), detail.payload());
        return switch (route.ownerService()) {
            case "eap-wallet" -> wallet.brokerReplayPreflight(sourceToken, request);
            case "eap-order" -> order.brokerReplayPreflight(sourceToken, request);
            default -> new BrokerReplayPreflightResult(
                    BrokerReplayPreflightDecision.UNSUPPORTED_ROUTE,
                    false,
                    "Replay route has no authoritative owner preflight",
                    null,
                    null);
        };
    }

    public BrokerReplayPreflightResult execute(
            RecoveryCaseDetail detail,
            String sourceToken,
            UUID actionId) {
        BrokerReplayPreflightResult preflight = preflight(detail, sourceToken);
        if (preflight.safeToReplay()) {
            BrokerReplayRouteRegistry.ReplayRoute route = routes.resolve(detail)
                    .orElseThrow(() -> new IllegalStateException("Replay route changed after preflight"));
            publisher.publish(detail, route, actionId);
            postConfirmProbes.forEach(probe -> probe.afterBrokerConfirmationBeforeAudit(
                    actionId, detail.summary().caseId(), route.queue()));
        }
        return preflight;
    }
}
