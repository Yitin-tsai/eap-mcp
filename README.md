# EAP MCP Server

`eap-mcp` is the control-plane service for the EAP workspace.

It exposes MCP tools and a small set of HTTP/Feign-backed helpers so an LLM or other client can inspect the market, register users, place orders, and run simulations.

## Responsibilities

- Expose MCP tools for trading, market data, user management, and simulation
- Bridge LLM calls to `eap-order` and `eap-wallet`
- Keep the control plane separate from the core RabbitMQ-driven trading flow

## What this service is not

- It is not the main order lifecycle engine
- It is not the wallet state machine
- It is not the match engine

Those responsibilities stay in the core services. This module is just the access layer.

## Key modules

- `client/` - sync calls to `eap-order` and `eap-wallet`
- `tools/mcp/` - MCP tool implementations
- `simulation/` - market simulation engine for LLM-driven workflows

`AuctionMcpTool` exists in source, but it is not registered in the current `McpToolConfig`. Auction operations are therefore not part of the MCP server's currently exposed tool set.

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
- MCP is a control-plane experiment. It is excluded from core capacity and correctness claims.
- Do not expose production secrets or unrestricted state-changing tools to an unapproved model or client.
