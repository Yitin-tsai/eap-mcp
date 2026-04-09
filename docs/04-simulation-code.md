# 04 — Simulation 工具（Code excerpts & explanations）

本篇將對 Simulation 的重要程式碼片段與逐行說明。

1) SimulationRequest（完整欄位與預設值）

```java

public class SimulationRequest {
  private String strategy = "simple";
  private String symbol = "ELC";
  private int steps = 10;
  private boolean executeReal = false; // if true, call placeOrder
  private String userId;

  // Configurable simulation params
  private double threshold = 0.01; // minimum spread to act on
  private int qty = 1; // units per order
  private String priceStrategy = "mid"; // topBid | mid | topAsk
  private String sides = "BOTH"; // BUY | SELL | BOTH
  private int ordersPerStep = 1; // how many orders per side each step


}
```

2) SimulationResult（回傳結構）

```java
public class SimulationResult {
  private String symbol;
  private int steps;
  private List<String> events = new ArrayList<>();
  private List<com.eap.common.dto.PlaceOrderResponse> fills = new ArrayList<>();
  private List<com.eap.common.dto.MarketMetricsResponse> marketSnapshots = new ArrayList<>();
  private List<SimulationFill> parsedFills = new ArrayList<>();

  // getters/setters omitted for brevity
}
```

說明：
- `events` 用來記錄模擬過程中的人類可讀日誌。
- `parsedFills` 為便於報表計算的結構化摘要，通常包含 pre/post best bid/ask 以及執行價格、數量等。

3) SimulationService — 關鍵執行邏輯摘錄

下面為 `runSimulation` 的核心功能

(a) 每步抓快照 + 取 topBid/topAsk
```java
var metrics = metricsTool.getMarketMetrics();
BigDecimal topBid = BigDecimal.ZERO, topAsk = BigDecimal.ZERO;
if (metrics != null && metrics.getOrderBook() != null) {
  var ob = metrics.getOrderBook();
  if (ob.getBids()!=null && !ob.getBids().isEmpty() && ob.getBids().get(0)!=null)
    topBid = BigDecimal.valueOf(ob.getBids().get(0).getPrice());
  if (ob.getAsks()!=null && !ob.getAsks().isEmpty() && ob.getAsks().get(0)!=null)
    topAsk = BigDecimal.valueOf(ob.getAsks().get(0).getPrice());
}
result.getEvents().add("step:"+step+" bid:"+topBid+" ask:"+topAsk);

```

(b) 觸發條件與單步多張

```java
BigDecimal spread = topAsk.subtract(topBid);
if (topAsk.compareTo(topBid) > 0 && spread.compareTo(threshold) > 0) {
  for (int i = 0; i < Math.max(1, ordersPerStep); i++) {
    // BUY
    if ("BUY".equalsIgnoreCase(sides) || "BOTH".equalsIgnoreCase(sides)) {
      BigDecimal px = selectPrice(topBid, topAsk, priceStrategy);
      if (req.isExecuteReal()) {
        var r = tradingTool.placeOrder(req.getUserId(),"BUY",px.toPlainString(),String.valueOf(qty),req.getSymbol());
        result.getEvents().add("placed real BUY: " + r.getMessage());
        result.getFills().add(r);
        // 真單後抓快照並建構 parsed fill
        try {
          var post = metricsTool.getMarketMetrics();
          com.eap.common.dto.MarketMetricsResponse prev =
              result.getMarketSnapshots().isEmpty()? null : result.getMarketSnapshots().getLast();
          result.getMarketSnapshots().add(post);

          SimulationFill f = new SimulationFill();
          f.setOrderId(r.getOrderId());
          f.setSide(r.getSide());
          f.setExecutedPrice(r.getPrice());
          f.setExecutedQty(r.getQty());
          f.setStatus(r.getStatus());
          f.setMessage(r.getMessage());
          f.setSymbol(r.getSymbol());
          f.setStep(step);
          f.setTimestamp(r.getAcceptedAt());
          // pre/post best bid/ask（若 prev 為空則略過）
          // ...（依你程式碼對 prev/post 讀取 bids[0]/asks[0]）
          result.getParsedFills().add(f);
        } catch (Exception e) {
          result.getEvents().add("snapshot error:" + e.getMessage());
        }
      } else {
        result.getEvents().add("simulated BUY: user="+req.getUserId()+" "+px+"x"+qty);
      }
    }
    // SELL 分支同理（略）
  }
}
```

(c) 價格策略
```java
private BigDecimal selectPrice(BigDecimal bid, BigDecimal ask, String s) {
  if ("topBid".equalsIgnoreCase(s) || "bid".equalsIgnoreCase(s)) return bid;
  if ("topAsk".equalsIgnoreCase(s) || "ask".equalsIgnoreCase(s)) return ask;
  try { return bid.add(ask).divide(BigDecimal.valueOf(2)); } catch (Exception e) { return bid; }
}

```

4) 以 MCP 工具暴露：SimulationMcpTool
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SimulationMcpTool {

  private final SimulationService simulationService;

  @Tool(name="runSimulation", description="執行交易模擬；若 executeReal=true 則會呼叫實際下單")
  public SimulationResult runSimulation(@ToolParam(description="JSON 格式的模擬請求", required=true)
                                        SimulationRequest req) {
    log.info("runSimulation: {}", req);
    return simulationService.runSimulation(req);
  }

  @Tool(name="exportReport", description="匯出最近一次模擬的報表（直接回傳 SimulationResult）")
  public SimulationResult exportReport(@ToolParam(description="Simulation id / placeholder") String id) {
    return Optional.ofNullable(simulationService.getLastSimulationResult())
                   .orElseGet(() -> { var r=new SimulationResult();
                                      r.setSymbol("N/A"); r.setSteps(0); r.getEvents().add("no simulation run yet");
                                      return r; });
  }
}
```
- 以 @Tool/@ToolParam 直接讓 LLM/Agent 可呼叫；runSimulation 會把所有行為封裝在 SimulationService。

- exportReport 利用 volatile 緩存的 lastSimulationResult 回傳最近一次結果，便於外部快速取得。

---
