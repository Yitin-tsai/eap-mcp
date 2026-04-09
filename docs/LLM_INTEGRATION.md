# LLM 平台 MCP 集成指南

## 🤖 Claude Desktop 集成

### 配置文件位置
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

### 配置內容
```json
{
  "mcpServers": {
    "eap-trading": {
      "command": "python3",
      "args": ["/path/to/eap/eap-mcp/scripts/mcp_bridge.py"],
      "env": {
        "EAP_MCP_URL": "http://localhost:8083",
        "EAP_MCP_TIMEOUT": "30"
      }
    }
  }
}
```

## 🦾 OpenAI GPTs 集成

### Actions 配置
```yaml
openapi: 3.0.1
info:
  title: EAP Trading MCP API
  description: 電力交易平台 MCP 工具接口
  version: 1.0.0
servers:
  - url: http://localhost:8083
paths:
  /mcp/tools/getOrderBook/call:
    post:
      operationId: getOrderBook
      summary: 獲取訂單簿數據
      requestBody:
        required: false
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
                  properties:
                    depth:
                      type: integer
                      description: 返回的深度層數，預設 10
      responses:
        '200':
          description: 成功

  /mcp/tools/getMarketMetrics/call:
    post:
      operationId: getMarketMetrics
      summary: 獲取市場即時指標
      requestBody:
        required: false
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
      responses:
        '200':
          description: 成功

  /mcp/tools/placeOrder/call:
    post:
      operationId: placeOrder
      summary: 下單交易
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
                  required:
                    - userId
                    - side
                    - price
                    - qty
                  properties:
                    userId:
                      type: string
                      format: uuid
                    side:
                      type: string
                      enum: [BUY, SELL]
                    price:
                      type: string
                    qty:
                      type: string
                    symbol:
                      type: string
                      description: 預設 ELC，可自訂標的
      responses:
        '200':
          description: 成功

  /mcp/tools/getUserOrders/call:
    post:
      operationId: getUserOrders
      summary: 查詢用戶歷史訂單
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
                  required:
                    - userId
                  properties:
                    userId:
                      type: string
                      format: uuid
      responses:
        '200':
          description: 成功

  /mcp/tools/cancelOrder/call:
    post:
      operationId: cancelOrder
      summary: 取消指定訂單
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
                  required:
                    - orderId
                  properties:
                    orderId:
                      type: string
      responses:
        '200':
          description: 成功

  /mcp/tools/registerUser/call:
    post:
      operationId: registerUser
      summary: 註冊新用戶並配發預設錢包
      requestBody:
        required: false
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
      responses:
        '200':
          description: 成功

  /mcp/tools/getUserWallet/call:
    post:
      operationId: getUserWallet
      summary: 查詢用戶錢包狀態
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
                  required:
                    - userId
                  properties:
                    userId:
                      type: string
                      format: uuid
      responses:
        '200':
          description: 成功

  /mcp/tools/checkUserExists/call:
    post:
      operationId: checkUserExists
      summary: 檢查用戶是否存在
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                arguments:
                  type: object
                  required:
                    - userId
                  properties:
                    userId:
                      type: string
                      format: uuid
      responses:
        '200':
          description: 成功
```

### 可用工具一覽
1. **getOrderBook** — 查詢指定深度的訂單簿。
2. **getMarketMetrics** — 取得最新市場價差、成交量等指標。
3. **placeOrder** — 送出買/賣訂單，支援自訂標的。
4. **getUserOrders** — 查詢特定用戶的歷史訂單列表。
5. **cancelOrder** — 取消指定訂單。
6. **registerUser** — 自動註冊新用戶並建立預設錢包餘額。
7. **getUserWallet** — 查詢用戶錢包的可用/鎖定餘額。
8. **checkUserExists** — 驗證用戶 ID 是否存在於系統。

## 🧠 Other LLM Integrations

### LangChain 集成
```python
from langchain.tools import Tool
from langchain.agents import initialize_agent
import requests

def create_eap_tools():
    def call_mcp_tool(tool_name: str, **kwargs):
        response = requests.post(
            f"http://localhost:8083/mcp/tools/{tool_name}/call",
            json={"arguments": kwargs}
        )
        return response.json()
    
    tools = [
        Tool(
            name="get_order_book",
            description="獲取訂單簿數據",
            func=lambda **kwargs: call_mcp_tool("getOrderBook", **kwargs)
        ),
        Tool(
            name="get_market_metrics",
            description="獲取市場指標", 
            func=lambda **kwargs: call_mcp_tool("getMarketMetrics", **kwargs)
        ),
        Tool(
            name="place_order", 
            description="下單交易",
            func=lambda **kwargs: call_mcp_tool("placeOrder", **kwargs)
        ),
        Tool(
            name="get_user_orders",
            description="查看用戶歷史訂單",
            func=lambda **kwargs: call_mcp_tool("getUserOrders", **kwargs)
        ),
        Tool(
            name="cancel_order",
            description="取消指定訂單",
            func=lambda **kwargs: call_mcp_tool("cancelOrder", **kwargs)
        ),
        Tool(
            name="register_user",
            description="註冊新用戶並創建錢包",
            func=lambda **kwargs: call_mcp_tool("registerUser", **kwargs)
        ),
        Tool(
            name="get_user_wallet",
            description="查詢用戶錢包狀態",
            func=lambda **kwargs: call_mcp_tool("getUserWallet", **kwargs)
        ),
        Tool(
            name="check_user_exists",
            description="檢查用戶是否存在",
            func=lambda **kwargs: call_mcp_tool("checkUserExists", **kwargs)
        )
    ]
    
    return tools

# 使用示例
tools = create_eap_tools()
agent = initialize_agent(tools, llm, agent_type="zero-shot-react-description")
```

### LlamaIndex 集成
```python
from llama_index.tools import FunctionTool
import requests

def get_order_book(depth: int = 10):
    """獲取訂單簿數據"""
    response = requests.post(
        "http://localhost:8083/mcp/tools/getOrderBook/call",
        json={"arguments": {"depth": depth}}
    )
    return response.json()

def place_order(user_id: str, side: str, price: str, qty: str, symbol: str = "ELC"):
    """下單交易"""
    response = requests.post(
        "http://localhost:8083/mcp/tools/placeOrder/call", 
        json={"arguments": {
            "userId": user_id,
            "side": side, 
            "price": price,
            "qty": qty,
            "symbol": symbol
        }}
    )
    return response.json()

def get_user_wallet(user_id: str):
    """查詢錢包狀態"""
    response = requests.post(
        "http://localhost:8083/mcp/tools/getUserWallet/call",
        json={"arguments": {"userId": user_id}}
    )
    return response.json()

# 創建工具
tools = [
    FunctionTool.from_defaults(fn=get_order_book),
    FunctionTool.from_defaults(fn=place_order),
    FunctionTool.from_defaults(fn=get_user_wallet)
]
```

## 🔐 Production 注意事項

### 安全性
- 實現 API Key 認證
- 使用 HTTPS
- 添加 IP 白名單
- 實現 Rate Limiting

### 監控
- 添加請求日誌
- 設置告警
- 監控 API 使用量
- 性能指標收集

### 示例生產配置
```yaml
# docker-compose.yml
version: '3.8'
services:
  eap-mcp:
    image: eap-mcp:latest
    ports:
      - "8083:8083"
    environment:
      - SPRING_PROFILES_ACTIVE=production
      - EAP_MCP_API_KEY=${EAP_MCP_API_KEY}
      - EAP_MCP_ALLOWED_ORIGINS=${EAP_MCP_ALLOWED_ORIGINS}
    volumes:
      - ./config:/app/config
    networks:
      - eap-network
```
