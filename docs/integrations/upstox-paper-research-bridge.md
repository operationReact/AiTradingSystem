# Upstox Analytics → MintTick scanner → PAPER readiness (research-only slice)

Goal: start exercising MintTick's existing deterministic scanner and PAPER simulator
without waiting for advanced machine-learning model training.

## Implemented in this slice
- `UpstoxResearchScanBridge` transforms **in-memory, previously validated** read-only
  Upstox Analytics equity candles into the existing `ScanContext` and invokes
  existing `ScannerEngine`. It does not duplicate RSI / breakout / moving-average strategies.
- Strict chronological, non-future, positive OHLC, range, volume and exchange-prefix
  checks reject unsafe candidate input. Existing `ScannerEngine` enforces freshness.
- The returned `Preview` always has `executionEnabled=false` and
  `instrumentMappingVerified=false`; suggestions are **research-only**.
- No PostgreSQL writes, raw candle storage, scan repository writes, background
  scheduler, paper orders, broker orders, changes to existing Angel One worker,
  Upstox OAuth, holdings or funds. Tests use synthetic candles only.
- The `UpstoxAnalyticsSource` multi-timeframe extension is a separate PR #917;
  this class requires only its existing `Candle` record so it can merge independently.

## Required before automatic PAPER
1. Verify genuine analytics-token reads via existing protected one-shot probe
   (nonzero validated candles + ratios). The successful customer OAuth login does
   **not** prove the independent SSM Analytics source works.
2. Map Upstox `NSE_EQ|ISIN` / `BSE_EQ|ISIN` to MintTick stable listing identity.
   This bridge checks exchange prefix but cannot establish ISIN-to-listing identity.
3. Verify permitted data usage, publication timestamp, completeness, freshness,
   and any applicable source-retention and model-training rights.
4. Populate suitable complete intraday candle windows, then run deterministic
   scanner and measure hit rate, slippage, fees and drawdown on PAPER-only
   positions through the **existing** order gateway (never directly from this bridge).
5. Independently verify PAPER mode authority, room virtual wallet, market-session
   state, fresh exchange ticks, risk checks, idempotency and synthetic fills.
6. Keep real-money order routing explicitly disabled.

## Existing components to reuse
- `scanner/ScannerEngine` and `VolumeBreakoutStrategy`,
  `MovingAverageReclaimStrategy`, `RsiReversalStrategy`
- `tradingrooms/CustomerPaperOrderService` for manual simulated CASH equity orders
- `paper/AutomatedPaperOrderService` for separate authority-gated automated PAPER orders

The historical API alone is **not a live tick feed**. A PAPER fill must use a
fresh authoritative market tick. Do not fill virtual orders at yesterday's close
or at the historical request's sample price.

References:
- https://upstox.com/developer/api-documentation/analytics-token/
- https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/
- https://upstox.com/developer/api-documentation/rate-limiting/
