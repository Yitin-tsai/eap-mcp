# EAP MCP Server

EAP 電力交易平台的 Model Context Protocol (MCP) 服務器，為 LLM 提供標準化的交易工具接口。

## 功能概述

### 核心功能
- **標準化工具接口**: 提供符合 MCP 規範的工具定義和執行接口
- **市場數據查詢**: 獲取實時訂單簿、交易記錄和市場指標
- **訂單管理**: 支持下單、取消、查詢等操作（Phase 2）
- **風險控制**: 內建頻率限制和錯誤處理機制
 

### 架構設計
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   LLM Client    │───▶│   EAP MCP       │───▶│  Order Service  │
│   (Ollama)      │    │   Server        │    │                 │
└─────────────────┘    │  Match Engine   │    └─────────────────┘             
                       └─────────────────┘
```

## 快速開始

### 前置需求
- Java 17+
- eap-order 服務運行在 localhost:8081
- eap-matchEngine 服務運行在 localhost:8082

### 啟動服務

1. **構建項目**
```bash
./gradlew build
```

2. **啟動服務**
```bash
./gradlew bootRun
```

服務將在 http://localhost:8083 啟動

### 驗證部署

```bash
# 檢查服務狀態
curl http://localhost:8083/mcp/health

# 獲取服務資訊
curl http://localhost:8083/mcp/info

# 查看可用工具
curl http://localhost:8083/mcp/tools
```

## API 文檔

### MCP 標準端點

#### 獲取服務器資訊
```http
GET /mcp/info
```

#### 列出所有工具
```http
GET /mcp/tools
```

#### 執行工具
```http
POST /mcp/tools/{toolName}/call
Content-Type: application/json

{
  "arguments": {
    "symbol": "ELC",
    "depth": 10
  }
}
```

### 可用工具 (Phase 1)

#### 1. getOrderBook
獲取訂單簿數據

**參數**:
- `symbol` (string, 可選): 交易標的，預設 "ELC"
- `depth` (integer, 可選): 深度層數，預設 10

**回應**: 包含買賣盤資訊的訂單簿數據

#### 2. getTrades
獲取交易記錄

**參數**:
- `symbol` (string, 可選): 交易標的，預設 "ELC"
- `since` (string, 可選): 開始時間 (ISO 8601)
- `cursor` (string, 可選): 分頁游標
- `limit` (integer, 可選): 記錄數量，預設 50

**回應**: 交易記錄列表

#### 3. metrics
獲取市場指標

**參數**:
- `symbol` (string, 可選): 交易標的，預設 "ELC"
- `window` (string, 可選): 時間窗口，預設 "1h"
- `depthN` (integer, 可選): 深度分析層數，預設 5

**回應**: 包含價差、成交量、波動率等指標

## 開發指南

### 項目結構
```
src/main/java/com/eap/mcp/
├── EapMcpApplication.java          # 主應用程式
├── client/                         # Feign / HTTP 客戶端
│   ├── OrderServiceClient.java     # Order Service 客戶端
│   └── WalletServiceClient.java    # Wallet / User 客戶端
├── config/                         # 配置類
│   ├── McpToolConfig.java          # 將 @Tool 註解的方法匯出為工具
│   └── RestTemplateConfig.java     # RestTemplate / HTTP 設定
└── tools/                         # MCP 工具實現
  └── mcp/
    ├── OrderBookMcpTool.java       # 訂單簿工具 (getOrderBook)
    ├── MarketMetricsMcpTool.java    # 市場指標工具 (getMarketMetrics)
    ├── TradingMcpTool.java          # 交易工具 (placeOrder, cancelOrder, getUserOrders)
    └── UserManagementMcpTool.java   # 用戶/錢包管理工具 (registerUser, getUserWallet, checkUserExists)
```

### 配置文件

#### application.yml
```yaml
server:
  port: 8083

eap:
  order-service:
    base-url: http://localhost:8081
  match-engine:
    base-url: http://localhost:8082

mcp:
  rate-limit:
    enabled: true
    requests-per-minute: 60
```

### 添加新工具（使用 Spring @Tool）

本專案支援以 Spring 的註解方式暴露 MCP 工具：使用 `@Tool` 標記方法，並用 `@ToolParam` 描述參數。`McpToolConfig` 會掃描並自動將這些註解的方法註冊為 MCP 可呼叫的工具。

1. **創建工具類（範例）**
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class NewTool {
  @Tool(name = "toolName", description = "工具描述")
  public Map<String, Object> toolName(
    @ToolParam(description = "參數 a", required = true) String a,
    @ToolParam(description = "參數 b", required = false) Integer b
  ) {
    // 工具邏輯
    return Map.of("ok", true);
  }
}
```

2. **自動註冊**

`McpToolConfig` 會在啟動時掃描 Spring 容器中帶有 `@Tool` 註解的方法並將其匯出為 MCP 工具清單，無需手動改動 `McpToolService` 的程式碼來註冊單一工具。

3. **驗證工具可用性**

啟動後可透過以下 API 查看已註冊的工具：

```bash
curl http://localhost:8083/mcp/tools
```
若工具未出現在列表，請檢查該類是否為 Spring 管理的 bean（例如 `@Component`）以及是否正確使用 `@Tool` 註解。

## Phase 2 規劃

### 已有／預定的工具（Phase 1 / Phase 2 範例）

目前 MCP 模組已提供下列可被 LLM 或外部客戶端呼叫的工具（方法名稱即為工具名稱）：

- `getOrderBook(depth?)` — 取得訂單簿（對應 `OrderBookMcpTool.getOrderBook`）
- `getMarketMetrics()` — 取得市場指標（對應 `MarketMetricsMcpTool.getMarketMetrics`）
- `placeOrder(userId, side, price, qty, symbol?)` — 下單（對應 `TradingMcpTool.placeOrder`）
- `cancelOrder(orderId)` — 取消訂單（對應 `TradingMcpTool.cancelOrder`）
- `getUserOrders(userId)` — 查詢用戶訂單（對應 `TradingMcpTool.getUserOrders`）

## 與 LLM 的互動建議

關於 LLM 的系統提示（SYSTEM_PROMPT）、模型使用範例與如何讓 LLM 呼叫 MCP 工具的詳細指引，請移至 `eap/eap-ai-client/README.md`。AI client 將負責與模型的交互與提示設定。


### 健康檢查
```bash
curl http://localhost:8083/actuator/health
```

### 指標收集
```bash
curl http://localhost:8083/actuator/metrics
curl http://localhost:8083/actuator/prometheus
```

### 日誌級別
- 開發環境: DEBUG
- 生產環境: INFO

## 安全考量

### 頻率限制
- 每分鐘最多 60 次請求
- 突發容量: 10 次請求

### 錯誤處理
- 統一的錯誤格式
- 不暴露內部實現細節
- 完整的錯誤追蹤



