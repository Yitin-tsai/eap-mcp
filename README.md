# EAP MCP Server

`eap-mcp` is the control-plane service for the EAP workspace.

It exposes MCP tools for market inspection, user registration, order placement and
simulation. Its separate, protected recovery HTTP API aggregates owner-local
terminal work, persists broker dead letters and records operator actions.

## Responsibilities

- Expose MCP tools for trading, market data, user management, and simulation
- Bridge LLM calls to `eap-order` and `eap-wallet`
- Aggregate recovery cases from Order, Wallet and MatchEngine without writing their schemas
- Persist shared-DLQ quarantine and central recovery action audit in PostgreSQL
- Run single-case dry-run/replay/park/resolve under owner-side eligibility rules
- Keep the control plane separate from the core RabbitMQ-driven trading flow

## What this service is not

- It is not the main order lifecycle engine
- It is not the wallet state machine
- It is not the match engine

Those responsibilities stay in the core services. Recovery eligibility and domain
state transitions remain with each owner; this service owns the central quarantine,
case disposition and operator audit.

## Key modules

- `client/` - sync calls to `eap-order` and `eap-wallet`
- `tools/mcp/` - MCP tool implementations
- `simulation/` - market simulation engine for LLM-driven workflows
- `recovery/` - protected HTTP API, owner-source clients, quarantine, conditional broker replay and action audit

`AuctionMcpTool` exists in source, but it is not registered in the current `McpToolConfig`. Auction operations are therefore not part of the MCP server's currently exposed tool set.

## Failure recovery control plane

The recovery API is separate from MCP tool discovery and is not exposed as an LLM
tool. It supports inbox/outbox/cleanup terminal cases, warning-only Saga timeouts
and broker dead letters.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/internal/recovery/v1/cases` | Bounded case listing, including owner-source errors |
| GET | `/internal/recovery/v1/cases/{caseId}` | Payload, identity, error and current fingerprint |
| POST | `/internal/recovery/v1/cases/{caseId}/dry-run` | Check the requested action against current state |
| POST | `/internal/recovery/v1/cases/{caseId}/actions` | Audited single-case action |
| GET | `/internal/recovery/v1/actions/{actionId}` | Inspect the recorded result |

All requests require `X-EAP-Recovery-Operator-Token`; actions also require
`X-EAP-Operator`, a UUID `actionId`, `expectedFingerprint` and a reason. Retry an
uncertain response with the same action identity. Owner-side action ledgers and
central audit preserve the original result; action acceptance does not prove that
the whole trading workflow has completed.

Recovery is disabled by default. `EAP_RECOVERY_CONTROL_ENABLED` enables the API and,
by default, its PostgreSQL migrations and DB health check; configure
`EAP_RECOVERY_OPERATOR_TOKEN` and the owner-side `EAP_RECOVERY_SOURCE_TOKEN`.
`EAP_RECOVERY_DB_ENABLED` can explicitly override database initialization.
This token/operator contract is the implemented internal boundary, not full
production authentication or operator RBAC.

`EAP_RECOVERY_BROKER_DLQ_ENABLED` separately enables persist-before-ACK consumption
of `order.dlq`; it moves messages into PostgreSQL quarantine and is not a passive
inspection switch. Broker replay currently allows only exact transient
`TradeExecutedEvent` routes for `wallet.tradeExecuted.queue` and
`order.tradeExecuted.queue`, with owner business-state preflight. Other routes,
identity conflicts and permanent failures remain fail closed. There is no bulk
replay or automatic cross-service compensation.

See the [recovery guide](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/failure-recovery-control-plane.zh-TW.md),
[owner-aware replay ADR](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/adr/ADR-006-owner-aware-shared-dlq-replay.zh-TW.md)
and [REL-107 campaign](https://github.com/Yitin-tsai/eap-infra/blob/main/docs/benchmarks/2026-09-18-rel107-failure-injection-campaign.md)
for configuration, crash evidence and deferred scope.

## Start

```bash
./gradlew bootRun
```

Default port: `8083`

## Transport and health

- Spring AI MCP SSE connection: `/sse` under the configured server transport.
- Spring Boot health: `GET /actuator/health`.
- Tool discovery and invocation use MCP protocol negotiation; this repository has no custom `/mcp/tools/*` REST controller.

## Notes

- Tools should stay thin and delegate business decisions to the core services.
- If you add a new tool, also update the corresponding client or integration path.
- MCP trading/simulation tools are excluded from core capacity claims. The protected recovery path has its own REL-106/107 reliability evidence and does not own core transaction correctness.
- Do not expose production secrets or unrestricted state-changing tools to an unapproved model or client.
