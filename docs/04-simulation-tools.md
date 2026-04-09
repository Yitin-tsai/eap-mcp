MCP｜Simulation 設計理念、請求/回傳模型與模擬要點（上）

本篇聚焦專案中的「模擬下單」能力：在不改動撮合引擎與錢包服務的前提下，以**非侵入（或選擇性侵入）**的方式，讓 AI/腳本在每個 step 根據市場狀態做出下單決策，並回傳事件日誌與快照，方便後續評估策略成效。
## 1. 為什麼要做 Simulation？

- 非侵入驗證策略：不修改交易路徑就能演練「何時下單、下哪一邊、用什麼價位」。

- 可選真單：切換 `executeReal=true` 就能在 dev/test 做「真實下單 → 觀察市場回應」，與既有事件流無縫銜接（`Order → Wallet → Match`）。

- 可追蹤：回填 fills、前後市場快照與解析後的填單摘要（parsedFills），為 KPI 與報表打基礎。

## 2. `SimulationRequest` 參數說明

以下欄位與預設值均直接取自 `SimulationRequest` 類：

- `strategy` (String) — 預設 "simple"。目前 SimulationService 使用這個字串作為策略選擇的 placeholder（現行實作主要使用簡單閾值邏輯）。
- `symbol` (String) — 預設 "ELC"。交易標的。
- `steps` (int) — 預設 10。模擬次數（loop 次數）。
- `executeReal` (boolean) — 預設 false。是否在符合條件時呼叫 `TradingMcpTool.placeOrder` 執行真實下單。
- `userId` (String) — 要用於下單的 userId（當 `executeReal=true` 時必須提供有效的 UUID 字串）。

可調整的模擬參數（會直接影響每一步的下單判斷）：

- `threshold` (double) — 預設 0.01。最小 spread（topAsk - topBid）才會觸發下單。單位與價格相同（例如貨幣單位）。
- `qty` (int) — 預設 1。每筆訂單的數量（units）。
- `priceStrategy` (String) — 預設 "mid"。價格選擇策略，支援：
  - `topBid` 或 `bid` — 選用最高買價。
  - `topAsk` 或 `ask` — 選用最低賣價。
  - `mid`（預設）— 以 (topBid+topAsk)/2 為價格。
- `sides` (String) — 預設 "BOTH"。可為 `BUY`、`SELL` 或 `BOTH`。
- `ordersPerStep` (int) — 預設 1。每個 step 對於每個方向要下多少張單（至少 1）。

示例 JSON 輸入（模擬，不下真單）：

```json
{
  "strategy": "simple",
  "symbol": "ELC",
  "steps": 20,
  "executeReal": false,
  "threshold": 0.02,
  "qty": 2,
  "priceStrategy": "mid",
  "sides": "BOTH",
  "ordersPerStep": 1
}
```

示例 JSON 輸入（開啟真實下單）：

```json
{
  "executeReal": true,
  "userId": "00000000-0000-0000-0000-000000000000",
  "steps": 5,
  "threshold": 0.03
}
```

## 3. `SimulationResult` 結構

`SimulationResult` 包含：

- `symbol` (String) — 模擬標的。
- `steps` (int) — 模擬進行次數。
- `events` (List<String>) — 逐步記錄的事件日誌（例如：step:3 bid:100 ask:102，或 "simulated BUY: user=... 101x1"）。
- `fills` (List<PlaceOrderResponse>) — 當 `executeReal=true` 時，放入真單服務回傳的 `PlaceOrderResponse` 物件（含 orderId / status / message / price / qty / acceptedAt 等欄位）。
- `marketSnapshots` (List<MarketMetricsResponse>) — 每次真單下完後重新抓取的市場快照，便於比對下單前後的市況。
- `parsedFills` (List<SimulationFill>) — 解析過的填單摘要（包含 pre/post best bid/ask、執行價格、數量、step、timestamp 等），便於報表與 KPI 計算。

## 4. 模擬要點與實作細節

- 取得 topBid/topAsk：`SimulationService` 會透過 `MarketMetricsMcpTool.getMarketMetrics()` 取得聚合市場資料，然後讀取 `orderBook.bids` 與 `orderBook.asks` 的最高/最低價格 作為 topBid/topAsk。
- 判斷下單：計算 `spread = topAsk - topBid`，當 `spread > threshold` 時才會進行下一步（依 `sides` 決定 BUY/SELL）。
- 價格選取：`selectPrice(topBid, topAsk, priceStrategy)` 實作支援 `topBid`/`topAsk`/`mid`。
- 每個 step 可以下多筆：迴圈會根據 `ordersPerStep` 產生多張訂單（對各方向分別下單）。
- 真單後流程：在 `executeReal=true` 下，Service 會呼叫 `tradingTool.placeOrder(...)`、把回傳的 `PlaceOrderResponse` 新增到 `fills`，隨後呼叫 `metricsTool.getMarketMetrics()` 存到 `marketSnapshots`，並從前後快照填入 `SimulationFill` 的 pre/post best bid/ask 值。
- 失敗/錯誤處理：各步驟以 try/catch 包覆，任何 exception 都會被記錄到 `events`（例如 "snapshot error: ..."），避免整個模擬中斷。

  下一篇文章將會介紹程式碼與實作細節（包括 DTO 與 run loop 範例），介紹實際上我是怎麼設計讓ai能夠根據市場情況進行下單。
