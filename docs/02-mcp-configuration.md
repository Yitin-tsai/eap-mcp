# 02 - MCP 設定與初始化（Configuration）

本篇說明在 `eap-mcp` 模組中，如何為 MCP 工具平台做必要的設定：包含 Tool 註冊、MCP client 初始化以及整合外部服務（例如 Wallet / Order）。

## MCP 需要的基本設定

1. Tool 掃描與註冊
   - 使用 Spring 的 Component 掃描（`@Component`）或專用註解把工具類別註冊為 Bean。
   - 若使用 `spring-ai` 或自家 MCP 框架，通常在工具類別上使用 `@Tool(name = "...", description = "...")` 對方法標注。

2. MCP 工具設定檔（說明）
    - 後續將介紹專案的實際 `application.yml` 與 `McpToolConfig.java` 來介紹我使用了哪些設定。

## 與外部服務整合（Feign）

在 `eap-mcp` 我們將 Wallet,Order 的呼叫抽成 `WalletServiceClient`, `OrderServiceClient`（Feign interface）。確保在啟動類或配置類上有 `@EnableFeignClients` 並把 interface 放在可掃描路徑中。

```java
@FeignClient(name = "wallet-service", url = "${wallet.service.url}")
public interface WalletServiceClient {
    @PostMapping("/wallet/register")
    UserRegistrationResponse registerUser();
}
```

---


## 本專案的 `application.yml`設定

下面是該專案實際使用的設定：

```yaml
spring:
    application:
        name: eap-mcp

    cloud:
        compatibility-verifier:
            enabled: false

    # Spring AI MCP configuration
    ai:
        mcp:
            server:
                enabled: true
                name: "EAP Trading MCP Server"
                version: "1.0.0"
                description: "Model Context Protocol server for EAP electricity trading platform"
                sse-endpoint: /mcp/sse
                sse-message-endpoint: /mcp/message

# External service configurations
eap:
    order-service:
        base-url: http://localhost:8080/eap-order
  
    wallet:
        base-url: http://localhost:8081/eap-wallet
  
    match-engine:
        base-url: http://localhost:8082/match-engine

# MCP Server configuration
mcp:
    server:
        name: "EAP Trading MCP Server"
        version: "1.0.0"
        description: "Model Context Protocol Server for EAP Electricity Trading Platform"
        max-connections: 100
        timeout: 30s
        # MCP 協議端點
        endpoints:
            base-path: "/mcp"
            sse-path: "/sse"
            websocket-path: "/ws"
  
    # Rate limiting configuration
    rate-limit:
        enabled: true
        requests-per-minute: 60
        burst-capacity: 10
  
    # Audit configuration
    audit:
        enabled: true
        log-level: INFO
        include-request-body: true
        include-response-body: false

# Logging configuration
logging:
    level:
        com.eap.mcp: DEBUG
        org.springframework.ai: DEBUG
        feign: DEBUG
        io.modelcontextprotocol: DEBUG
    pattern:
        console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level [%X{traceId},%X{spanId}] %logger{36} - %msg%n"

# Management endpoints
management:
    endpoints:
        web:
            exposure:
                include: health,info,metrics,prometheus
    endpoint:
        health:
            show-details: always
```

### `application.yml` 重點說明

- `ai.mcp.server`: Spring AI / MCP 的 metadata（name、version、SSE 路徑），這會被 MCP 框架用於註冊與 UI 顯示。
- `eap.*.base-url`: 宣告下游服務（order, wallet, match-engine）的位址，方便透過 Feign 或 RestTemplate 去呼叫。
- `mcp.server.endpoints`: 定義 MCP 協議的暴露端點（SSE / WebSocket），MCP client / UI 會透過這些路徑連線。
- `mcp.rate-limit`、`mcp.audit`: 基礎保護與審計設定，能夠限制模型快速濫用並紀錄每次請求的 payload（可選）。

## MCP 工具註冊：`McpToolConfig.java`

專案使用 `MethodToolCallbackProvider` 將多個帶有 `@Tool` 的 bean 一次性註冊為 MCP 可用的工具，下面是 `McpToolConfig.java`：

```java

/**
 * MCP 工具配置類
 * 將 @Tool 註解的工具類註冊為 ToolCallbackProvider
 */
@Configuration
public class McpToolConfig {

        @Bean
        public ToolCallbackProvider mcpTools(
                        MarketMetricsMcpTool metrics,
                        OrderBookMcpTool orderBook,
                        TradingMcpTool trading,
                        UserManagementMcpTool userManagement,
                        SimulationMcpTool simulationMcpTool) {

                return MethodToolCallbackProvider
                                .builder()
                                .toolObjects(metrics, orderBook, trading, userManagement, simulationMcpTool)
                                .build();
        }
}
```

### `McpToolConfig` 說明

- `MethodToolCallbackProvider`：把傳入的工具 bean（含 `@Tool` 的方法）掃描並包裝成 MCP 可呼叫的 callback provider。
- 這樣做的好處是：
    - 集中註冊，容易在單一配置點管理所有工具
    - 允許你注入任何需要的依賴（例如 Feign client、services）到工具中，而工具本身只關注業務邏輯

---

下一篇會展示如何實作一個實際的 MCP 工具（`@Tool`）並示範常見範例。