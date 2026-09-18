package com.eap.mcp.recovery;

import java.util.UUID;

/**
 * Observation seam after a replay has received a positive broker confirm but before
 * the recovery case disposition and action result are committed locally.
 */
@FunctionalInterface
public interface BrokerReplayPostConfirmProbe {

    void afterBrokerConfirmationBeforeAudit(
            UUID actionId,
            String caseId,
            String ownerQueue);
}
