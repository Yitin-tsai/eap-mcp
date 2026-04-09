#!/usr/bin/env python3
"""
EAP MCP Client Test Script
測試與 EAP Trading MCP Server 的連接和工具調用
"""

import json
import requests
import time
from typing import Dict, Any

class EapMcpClient:
    def __init__(self, base_url: str = "http://localhost:8083"):
        self.base_url = base_url
        self.session = requests.Session()
        self.session.headers.update({
            "Content-Type": "application/json",
            "User-Agent": "EAP-MCP-Client/1.0"
        })

    def get_server_info(self) -> Dict[str, Any]:
        """獲取服務器信息"""
        response = self.session.get(f"{self.base_url}/mcp/info")
        response.raise_for_status()
        return response.json()

    def list_tools(self) -> Dict[str, Any]:
        """列出所有可用工具"""
        response = self.session.get(f"{self.base_url}/mcp/tools")
        response.raise_for_status()
        return response.json()

    def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        """調用指定工具"""
        payload = {"arguments": arguments}
        response = self.session.post(
            f"{self.base_url}/mcp/tools/{tool_name}/call",
            json=payload
        )
        response.raise_for_status()
        return response.json()

    def health_check(self) -> Dict[str, Any]:
        """健康檢查"""
        response = self.session.get(f"{self.base_url}/mcp/health")
        response.raise_for_status()
        return response.json()

def main():
    print("🚀 EAP MCP Client 測試開始...")
    
    # 初始化客戶端
    client = EapMcpClient()
    
    try:
        # 1. 健康檢查
        print("\n📊 健康檢查...")
        health = client.health_check()
        print(f"✅ 服務狀態: {health['status']}")
        
        # 2. 獲取服務器信息
        print("\n📋 服務器信息...")
        info = client.get_server_info()
        print(f"✅ 服務名稱: {info['name']}")
        print(f"✅ 版本: {info['version']}")
        print(f"✅ 協議版本: {info['protocolVersion']}")
        
        # 3. 列出工具
        print("\n🛠️  可用工具...")
        tools = client.list_tools()
        for tool in tools['tools']:
            print(f"✅ {tool['name']}: {tool['description']}")
        
        # 4. 測試工具調用
        print("\n🔍 測試工具調用...")
        
        # 測試獲取訂單簿
        print("📊 獲取訂單簿...")
        orderbook = client.call_tool("getOrderBook", {"depth": 5})
        print("✅ 訂單簿獲取成功")
        
        # 測試獲取市場指標
        print("📈 獲取市場指標...")
        metrics = client.call_tool("getMetrics", {"depthN": 5})
        print("✅ 市場指標獲取成功")
        
        # 測試查詢用戶訂單（使用示例 UUID）
        print("👤 查詢用戶訂單...")
        orders = client.call_tool("getUserOrders", {
            "userId": "550e8400-e29b-41d4-a716-446655440000"
        })
        print("✅ 用戶訂單查詢成功")
        
        print("\n🎉 所有測試通過！MCP 服務運行正常。")
        
    except requests.exceptions.ConnectionError:
        print("❌ 連接失敗：請確保 MCP 服務運行在 localhost:8083")
    except requests.exceptions.HTTPError as e:
        print(f"❌ HTTP 錯誤：{e}")
    except Exception as e:
        print(f"❌ 未知錯誤：{e}")

if __name__ == "__main__":
    main()
