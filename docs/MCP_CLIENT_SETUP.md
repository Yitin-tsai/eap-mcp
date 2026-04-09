# MCP Client Configuration for EAP Trading Platform

## Server Information
- **Server Name**: EAP Trading MCP Server
- **Version**: 1.0.0
- **Protocol Version**: 2024-11-05
- **Base URL**: http://localhost:8083

## Connection Configuration

### For Claude Desktop
```json
{
  "mcpServers": {
    "eap-trading": {
      "command": "node",
      "args": ["/path/to/mcp-client.js"],
      "env": {
        "EAP_MCP_URL": "http://localhost:8083",
        "EAP_MCP_TIMEOUT": "30000"
      }
    }
  }
}
```

### For Python MCP Client
```python
import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def connect_to_eap_mcp():
    server_params = StdioServerParameters(
        command="curl",
        args=["-X", "GET", "http://localhost:8083/mcp/info"]
    )
    
    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            # Initialize the session
            await session.initialize()
            
            # List available tools
            tools = await session.list_tools()
            print("Available tools:", [tool.name for tool in tools.tools])
            
            return session

# Usage
session = asyncio.run(connect_to_eap_mcp())
```

### For JavaScript/Node.js MCP Client
```javascript
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');

async function connectToEapMcp() {
    const transport = new StdioClientTransport({
        command: 'curl',
        args: ['-X', 'GET', 'http://localhost:8083/mcp/info']
    });
    
    const client = new Client({
        name: "eap-trading-client",
        version: "1.0.0"
    }, {
        capabilities: {
            tools: {}
        }
    });
    
    await client.connect(transport);
    
    // List available tools
    const tools = await client.listTools();
    console.log('Available tools:', tools.tools.map(t => t.name));
    
    return client;
}

// Usage
connectToEapMcp().then(client => {
    console.log('Connected to EAP MCP Server');
});
```

## Available Tools
1. **getOrderBook** - 獲取訂單簿數據
2. **getUserOrders** - 查詢用戶訂單記錄  
3. **getMetrics** - 獲取市場關鍵指標
4. **placeOrder** - 下單交易
5. **cancelOrder** - 取消訂單

## Example Tool Calls

### Get Order Book
```bash
curl -X POST "http://localhost:8083/mcp/tools/getOrderBook/call" \
  -H "Content-Type: application/json" \
  -d '{"arguments": {"depth": 10}}'
```

### Place Order
```bash
curl -X POST "http://localhost:8083/mcp/tools/placeOrder/call" \
  -H "Content-Type: application/json" \
  -d '{"arguments": {"userId": "550e8400-e29b-41d4-a716-446655440000", "side": "BUY", "price": "100", "qty": "50"}}'
```

### Get Market Metrics
```bash
curl -X POST "http://localhost:8083/mcp/tools/getMetrics/call" \
  -H "Content-Type: application/json" \
  -d '{"arguments": {"depthN": 5}}'
```

## Authentication & Security
- Currently running on localhost:8083
- For production use, implement proper authentication
- Consider rate limiting and access controls
- Use HTTPS in production environments

## Troubleshooting
- Ensure all services are running (eap-mcp:8083, order-service:8080, match-engine:8082)
- Check service health: `curl http://localhost:8083/mcp/health`
- Verify tool availability: `curl http://localhost:8083/mcp/tools`
