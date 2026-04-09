# 03 - 撰寫 MCP Tools：從簡單查詢到狀態變更

本篇用專案內的實際範例說明如何在 `eap-mcp` 中撰寫 MCP 工具（Tool）。這些範例直接摘自 `eap-mcp/src/main/java/com/eap/mcp/tools/mcp`，並補充設計要點。

## 工具的實作規則

- 在工具類別上使用 `@Component`（或 `@Service`），方法上使用 `@Tool(name = "...", description = "...")`。
- 方法的參數可以用 `@ToolParam` 補上說明與是否為必填。
- 回傳型態可以是 DTO、布林或任意可序列化的物件；建議使用結構化 DTO（含 success / error 字段），方便上層 AI client 做判斷與重試。

以下範例皆為專案中的真實實作，已略為去除不必要部分以利閱讀。

## OrderBookMcpTool

用途：查詢市場訂單簿（買賣盤）。

重點：使用 `OrderServiceClient` 呼叫下游服務並在異常情況下回傳空的訂單簿以保護呼叫端。

```java

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderBookMcpTool {

    private final OrderServiceClient orderServiceClient;

    @Tool(name = "getOrderBook", description = "獲取電力交易訂單簿數據，包含買賣盤資訊")
    public OrderBookResponseDto getOrderBook(
        @ToolParam(description = "訂單簿深度，預設為10層", required = false) Integer depth
    ) {
        try {
            if (depth == null) {
                depth = 10;
            }
            log.info("獲取訂單簿，深度: {}", depth);
            ResponseEntity<OrderBookResponseDto> response = orderServiceClient.getOrderBook(depth);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return OrderBookResponseDto.builder()
                    .bids(List.of())
                    .asks(List.of())
                    .build();
            }
        } catch (Exception e) {
            log.error("獲取訂單簿失敗", e);
            return OrderBookResponseDto.builder()
                .bids(List.of())
                .asks(List.of())
                .build();
        }
    }
}
```

## MarketMetricsMcpTool

用途：取得市場指標（價格、成交量等）。

重點：簡潔的代理呼叫，並在下游異常時回傳 failure DTO。

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketMetricsMcpTool {

    private final OrderServiceClient orderServiceClient;

    @Tool(name = "getMarketMetrics", description = "獲取電力交易市場實時指標，包含價格、成交量等信息")
    public MarketMetricsResponse getMarketMetrics() {
        try {
            log.info("獲取市場指標");
            ResponseEntity<MarketMetricsResponse> response = orderServiceClient.getMarketMetrics(10);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return MarketMetricsResponse.failure("無法獲取市場指標，狀態碼: " + response.getStatusCode().value());
            }
        } catch (Exception e) {
            log.error("獲取市場指標失敗", e);
            return MarketMetricsResponse.failure("獲取市場指標失敗: " + e.getMessage());
        }
    }
}
```

## TradingMcpTool

用途：下單、查詢用戶訂單、取消訂單。

重點：對輸入做基礎驗證（例如 side）、將字串價格/數量轉為 BigDecimal，並呼叫下游 OrderService。所有狀態回傳都包裝為 DTO，包含失敗原因，便於 AI client 做錯誤處理。

```java

@Component
@RequiredArgsConstructor
@Slf4j
public class TradingMcpTool {

    private final OrderServiceClient orderServiceClient;

    @Tool(name = "placeOrder", description = "下單交易，支持買入和賣出訂單")
    public PlaceOrderResponse placeOrder(
        @ToolParam(description = "用戶ID，必須是有效的UUID格式", required = true) String userId,
        @ToolParam(description = "訂單方向：BUY 或 SELL", required = true) String side,
        @ToolParam(description = "訂單價格", required = true) String price,
        @ToolParam(description = "訂單數量", required = true) String qty,
        @ToolParam(description = "交易標的代碼", required = false) String symbol
    ) {
        try {
            if (symbol == null || symbol.isEmpty()) {
                symbol = "ELC";
            }
            if (!side.equals("BUY") && !side.equals("SELL")) {
                return PlaceOrderResponse.failure("side 參數必須是 'BUY' 或 'SELL'");
            }

            log.info("下單請求: userId={}, side={}, price={}, qty={}, symbol={}", 
                    userId, side, price, qty, symbol);

            PlaceOrderRequest orderRequest = PlaceOrderRequest.builder()
                .userId(userId)
                .side(side)
                .price(new java.math.BigDecimal(price))
                .qty(new java.math.BigDecimal(qty))
                .symbol(symbol)
                .build();

            ResponseEntity<PlaceOrderResponse> response = orderServiceClient.placeOrder(orderRequest);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return PlaceOrderResponse.failure("訂單執行失敗，狀態碼: " + response.getStatusCode().value());
            }

        } catch (Exception e) {
            log.error("訂單執行失敗", e);
            return PlaceOrderResponse.failure("訂單執行失敗: " + e.getMessage());
        }
    }

    @Tool(name = "getUserOrders", description = "查詢用戶的所有交易訂單")
    public UserOrdersResponse getUserOrders(
        @ToolParam(description = "用戶ID，必須是有效的UUID格式", required = true) String userId
    ) {
        try {
            log.info("獲取用戶訂單，用戶: {}", userId);
            ResponseEntity<UserOrdersResponse> response = orderServiceClient.getUserOrders(userId, null);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return UserOrdersResponse.failure(userId, "無法獲取用戶訂單，狀態碼: " + response.getStatusCode().value());
            }
        } catch (Exception e) {
            log.error("獲取用戶訂單失敗", e);
            return UserOrdersResponse.failure(userId, "獲取用戶訂單失敗: " + e.getMessage());
        }
    }

    @Tool(name = "cancelOrder", description = "取消指定的交易訂單")
    public CancelOrderResponse cancelOrder(
        @ToolParam(description = "要取消的訂單ID", required = true) String orderId
    ) {
        try {
            log.info("取消訂單: {}", orderId);
            ResponseEntity<CancelOrderResponse> response = orderServiceClient.cancelOrder(orderId);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return CancelOrderResponse.failure(orderId, "取消訂單失敗，狀態碼: " + response.getStatusCode().value());
            }
        } catch (Exception e) {
            log.error("取消訂單失敗", e);
            return CancelOrderResponse.failure(orderId, "取消訂單失敗: " + e.getMessage());
        }
    }
}
```

## UserManagementMcpTool

用途：用戶註冊、查詢錢包與檢查用戶是否存在。

重點：`registerUser()` 會呼叫 WalletService 並回傳 `UserRegistrationResponse`。`checkUserExists` 用於 AI client 在執行任何會變更狀態的操作前做最小驗證。

```java

@Slf4j
@Component
public class UserManagementMcpTool {

    @Autowired
    private WalletServiceClient walletServiceClient;

    @Tool(name = "registerUser", description = "註冊新用戶並創建錢包，新用戶將獲得初始餘額")
    public UserRegistrationResponse registerUser() {
        try {
            UserRegistrationResponse response = walletServiceClient.registerUser();
            if (response != null) {
                if (response.isSuccess()) {
                    log.info("用戶註冊成功: userId={}", response.getUserId());
                }
                return response;
            } else {
                return UserRegistrationResponse.failure("用戶註冊失敗: 服務未響應");
            }
        } catch (Exception e) {
            log.error("用戶註冊過程中發生異常", e);
            return UserRegistrationResponse.failure("用戶註冊失敗: " + e.getMessage());
        }
    }

    @Tool(name = "getUserWallet", description = "查詢指定用戶的錢包狀態，包括可用餘額和鎖定餘額")
    public WalletStatusResponse getUserWallet(@ToolParam(description = "用戶ID (UUID格式)", required = true) String userId) {
        try {
            UUID userUuid = UUID.fromString(userId);
            WalletStatusResponse wallet = walletServiceClient.getWalletStatus(userUuid);
            if (wallet != null) {
                return wallet;
            } else {
                return WalletStatusResponse.notFound(userUuid);
            }
        } catch (IllegalArgumentException e) {
            return WalletStatusResponse.notFound(UUID.randomUUID());
        } catch (Exception e) {
            log.error("查詢用戶錢包失敗", e);
            return WalletStatusResponse.notFound(UUID.randomUUID());
        }
    }

    @Tool(name = "checkUserExists", description = "檢查指定的用戶ID是否存在於系統中")
    public boolean checkUserExists(@ToolParam(description = "用戶ID (UUID格式)", required = true) String userId) {
        try {
            UUID userUuid = UUID.fromString(userId);
            log.info("檢查用戶存在性: userId={}", userUuid);
            WalletStatusResponse wallet = walletServiceClient.getWalletStatus(userUuid);
            boolean exists = wallet != null && wallet.isSuccess();
            log.info("用戶 {} 存在狀態: {}", userUuid, exists);
            return exists;
        } catch (IllegalArgumentException e) {
            log.error("無效的用戶ID格式: {}", userId);
            return false;
        } catch (Exception e) {
            log.error("檢查用戶存在性失敗", e);
            return false;
        }
    }
}
```


### 小結

這些工具的設計要點：

- 使用 @Tool 標記公開方法，回傳 DTO 以便 AI client 可檢查 success/failure。
- 工具內部應對外部呼叫失敗採取保護性回退（空結果、failure DTO），以避免 AI 在執行流程中崩潰。
- 所有會變更系統狀態的工具（下單、註冊）應在 AI client 層做額外的最小驗證，例如 `checkUserExists`，並由 AI client 決定是否觸發 `registerUser`（建議僅在 dev/test 環境自動註冊）。

---

下一篇會介紹我特別設計的simulation工具,讓ai能有工具依據我設定的參數幫我進行市場買賣的模擬