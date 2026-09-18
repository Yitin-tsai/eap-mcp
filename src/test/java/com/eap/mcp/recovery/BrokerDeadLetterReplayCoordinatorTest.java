package com.eap.mcp.recovery;

import com.eap.common.recovery.BrokerReplayPreflightDecision;
import com.eap.common.recovery.BrokerReplayPreflightRequest;
import com.eap.common.recovery.BrokerReplayPreflightResult;
import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryFailureClass;
import com.eap.mcp.recovery.client.WalletRecoverySourceClient;
import com.eap.mcp.recovery.client.OrderRecoverySourceClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.eap.common.constants.RabbitMQConstants.TRADE_EXCHANGE;
import static com.eap.common.constants.RabbitMQConstants.TRADE_EXECUTED_KEY;
import static com.eap.common.constants.RabbitMQConstants.ORDER_TRADE_EXECUTED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.WALLET_TRADE_EXECUTED_QUEUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerDeadLetterReplayCoordinatorTest {

    @Test
    void eligibleCaseShouldPublishDirectlyToOwnerRoute() {
        WalletRecoverySourceClient wallet = mock(WalletRecoverySourceClient.class);
        OrderRecoverySourceClient order = mock(OrderRecoverySourceClient.class);
        BrokerDeadLetterReplayPublisher publisher = mock(BrokerDeadLetterReplayPublisher.class);
        BrokerReplayPostConfirmProbe probe = mock(BrokerReplayPostConfirmProbe.class);
        BrokerDeadLetterReplayCoordinator coordinator = new BrokerDeadLetterReplayCoordinator(
                new BrokerReplayRouteRegistry(), wallet, order, publisher, List.of(probe));
        RecoveryCaseDetail detail = detail(WALLET_TRADE_EXECUTED_QUEUE);
        UUID actionId = UUID.randomUUID();
        when(wallet.brokerReplayPreflight(eq("token"), any())).thenReturn(
                new BrokerReplayPreflightResult(
                        BrokerReplayPreflightDecision.ELIGIBLE, true,
                        "not durably received", "trade-1", null));

        var result = coordinator.execute(detail, "token", actionId);

        assertThat(result.safeToReplay()).isTrue();
        var ordered = inOrder(publisher, probe);
        ordered.verify(publisher).publish(eq(detail), any(), eq(actionId));
        ordered.verify(probe).afterBrokerConfirmationBeforeAudit(
                actionId, detail.summary().caseId(), WALLET_TRADE_EXECUTED_QUEUE);
    }

    @Test
    void alreadyDurableCaseShouldNotPublishAgain() {
        WalletRecoverySourceClient wallet = mock(WalletRecoverySourceClient.class);
        OrderRecoverySourceClient order = mock(OrderRecoverySourceClient.class);
        BrokerDeadLetterReplayPublisher publisher = mock(BrokerDeadLetterReplayPublisher.class);
        BrokerReplayPostConfirmProbe probe = mock(BrokerReplayPostConfirmProbe.class);
        BrokerDeadLetterReplayCoordinator coordinator = new BrokerDeadLetterReplayCoordinator(
                new BrokerReplayRouteRegistry(), wallet, order, publisher, List.of(probe));
        when(wallet.brokerReplayPreflight(eq("token"), any())).thenReturn(
                new BrokerReplayPreflightResult(
                        BrokerReplayPreflightDecision.ALREADY_DURABLE, false,
                        "inbox owns retry", "trade-1", "FAILED_RETRYABLE"));

        var result = coordinator.execute(detail(WALLET_TRADE_EXECUTED_QUEUE), "token", UUID.randomUUID());

        assertThat(result.alreadyOwnedByConsumer()).isTrue();
        verify(publisher, never()).publish(any(), any(), any());
        verify(probe, never()).afterBrokerConfirmationBeforeAudit(any(), any(), any());
    }

    @Test
    void orderDeadLetterShouldUseOrderPreflightAndDirectOrderQueue() {
        WalletRecoverySourceClient wallet = mock(WalletRecoverySourceClient.class);
        OrderRecoverySourceClient order = mock(OrderRecoverySourceClient.class);
        BrokerDeadLetterReplayPublisher publisher = mock(BrokerDeadLetterReplayPublisher.class);
        BrokerDeadLetterReplayCoordinator coordinator = new BrokerDeadLetterReplayCoordinator(
                new BrokerReplayRouteRegistry(), wallet, order, publisher, List.of());
        RecoveryCaseDetail detail = detail(ORDER_TRADE_EXECUTED_QUEUE);
        when(order.brokerReplayPreflight(eq("token"), any())).thenReturn(
                new BrokerReplayPreflightResult(
                        BrokerReplayPreflightDecision.ELIGIBLE, true,
                        "not durably received", "trade-1", null));

        var result = coordinator.execute(detail, "token", UUID.randomUUID());

        assertThat(result.safeToReplay()).isTrue();
        ArgumentCaptor<BrokerReplayPreflightRequest> request =
                ArgumentCaptor.forClass(BrokerReplayPreflightRequest.class);
        verify(order).brokerReplayPreflight(eq("token"), request.capture());
        assertThat(request.getValue().sourceQueue()).isEqualTo(ORDER_TRADE_EXECUTED_QUEUE);
        verify(wallet, never()).brokerReplayPreflight(any(), any());
        ArgumentCaptor<BrokerReplayRouteRegistry.ReplayRoute> route =
                ArgumentCaptor.forClass(BrokerReplayRouteRegistry.ReplayRoute.class);
        verify(publisher).publish(eq(detail), route.capture(), any());
        assertThat(route.getValue().queue()).isEqualTo(ORDER_TRADE_EXECUTED_QUEUE);
        assertThat(route.getValue().ownerService()).isEqualTo("eap-order");
    }

    private RecoveryCaseDetail detail(String sourceQueue) {
        Instant now = Instant.parse("2026-09-18T01:00:00Z");
        RecoveryCaseSummary summary = new RecoveryCaseSummary(
                "case-1", "rabbitmq", RecoveryDebtType.BROKER_DEAD_LETTER,
                "order.dlq", "message-1", "CAPTURED", RecoveryFailureClass.TRANSIENT,
                3, now, now, "TIMEOUT", "timeout",
                Map.of(
                        "sourceQueue", sourceQueue,
                        "originalExchange", TRADE_EXCHANGE,
                        "originalRoutingKey", TRADE_EXECUTED_KEY),
                EnumSet.allOf(RecoveryActionType.class), "fingerprint");
        return new RecoveryCaseDetail(summary, "{\"tradeId\":\"trade-1\"}", Map.of());
    }
}
