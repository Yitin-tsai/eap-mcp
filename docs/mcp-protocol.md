# EAP MCP Server 說明文檔

## 概述
本文檔說明 eap-mcp 服務提供的 Model Context Protocol (MCP) 端點和工具。

## 基礎資訊
- **服務端口**: `8083`
- **協議**: Model Context Protocol (MCP)
- **傳輸方式**: Server-Sent Events (SSE)
- **MCP 版本**: 2024-11-05

## MCP 端點

### 默認 SSE 端點
- **URL**: `http://localhost:8083/sse`
- **協議**: Server-Sent Events
- **用途**: MCP 客戶端連接端點

## 可用工具 (Tools)

### 1. getMarketMetrics
獲取電力交易市場實時指標

**功能**: 獲取市場價格、成交量等關鍵指標
**參數**: 無
**返回**: 市場指標數據

```json
{
  "success": true,
  "data": {
    "price": "decimal",
    "volume": "decimal",
    "spread": "decimal"
  },
  "timestamp": "number"
}
```

### 2. getOrderBook
獲取電力交易訂單簿數據

**功能**: 獲取買賣盤資訊
**參數**:
- `depth` (可選): 訂單簿深度，預設為10層

```json
{
  "success": true,
  "data": {
    "bids": [
      {"price": "decimal", "quantity": "decimal"}
    ],
    "asks": [
      {"price": "decimal", "quantity": "decimal"}
    ]
  },
  "timestamp": "number"
}
```

### 3. placeOrder
下單交易

**功能**: 執行買入或賣出訂單
**參數**:
- `userId` (必需): 用戶ID
- `side` (必需): 訂單方向，"BUY" 或 "SELL"
- `price` (必需): 訂單價格
- `qty` (必需): 訂單數量
- `symbol` (可選): 交易標的代碼，預設為 "ELC"

```json
{
  "success": true,
  "data": {
    "orderId": "uuid",
    "status": "string",
    "message": "string"
  },
  "timestamp": "number"
}
```

### 4. cancelOrder
取消訂單

**功能**: 取消指定的訂單
**參數**:
- `orderId` (必需): 要取消的訂單ID

```json
{
  "success": true,
  "message": "訂單已提交取消請求",
  "orderId": "string",
  "timestamp": "number"
}
```

### 5. getUserOrders
查詢用戶訂單

**功能**: 獲取用戶的所有交易訂單
**參數**:
- `userId` (必需): 用戶ID
- `status` (可選): 訂單狀態過濾

```json
{
  "success": true,
  "data": {
    "orders": [
      {
        "orderId": "uuid",
        "side": "BUY|SELL",
        "price": "decimal",
        "quantity": "decimal",
        "status": "string"
      }
    ]
  },
  "timestamp": "number",
  "userId": "string"
}
```

## MCP 客戶端連接

### 使用 Claude Desktop 連接

在 Claude Desktop 配置文件中添加：

```json
{
  "mcpServers": {
    "eap-trading": {
      "command": "sse",
      "args": ["http://localhost:8083/sse"]
    }
  }
}
```

### 使用 VS Code MCP 插件

1. 安裝 MCP 相關插件
2. 配置連接到 `http://localhost:8083/sse`
3. 開始使用電力交易工具

## 錯誤處理

所有工具調用錯誤都會返回統一格式：

```json
{
  "success": false,
  "error": "錯誤描述",
  "statusCode": "HTTP狀態碼（如適用）"
}
```

## 依賴服務

- **eap-order**: 提供訂單處理功能 (端口 8080)
- **Redis**: 用於緩存和限流 (端口 6379)

## 啟動順序

1. 確保 Redis 服務運行
2. 啟動 eap-order 服務
3. 啟動 eap-mcp 服務
4. 使用 MCP 客戶端連接
