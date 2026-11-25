# STR-201 — IV Rank Mean Reversion (Strategy 32/43)

**Status: `DATA_BLOCKED`.** Deterministic non-executable **NIFTY 30-calendar-day constant-tenor at-the-money implied-volatility (ATM IV) rank** research classifier. It does **not** assert historical alpha, executable quotes, or that short-volatility selling is safe.

## What is actually measured

- Obtain **authentic source-reported annualized IV percentage** for a **30-day constant-tenor ATM NIFTY option volatility measure**, normalized by a permitted, traceable feed or documented licensed interpolation (`LICENSED_ATM_30D_INTERPOLATION`). A value `38` means **38% annualized**, not 0.38.
- Use **252 original-first-known completed trading sessions** in the JUnit validation (minimum configured 200 sessions, never simply 35 stock closes). Historical daily NIFTY OHLC candles and the normalized IV observation for the same session must align exactly, with verified source and observable original first-known timestamps. Do not mix unlike IV maturities, nearby raw option expiries or unrelated providers.
- For strictly earlier observations, `IV_rank_percentage = clamp(100 × (current30dIV − historicalMin30dIV) / (historicalMax30dIV − historicalMin30dIV), 0, 100)`. **IV Rank is NOT IV Percentile** and an observed high rank is not a profitable selling opportunity by itself.
- Historical observed IV must contain a **minimum non-degenerate range**; otherwise `NO_SIGNAL`. Current IV, history and source index rights must be verified as-of the decision time, not filled from placeholder numbers or tomorrow's close.
- Evaluate two *research regimes*, **not any order**:
  1. **High-IV pullback**: `rank >= highRankThreshold` **and** current IV at least a configured number of volatility **percentage points** *below* the maximum of the immediately preceding 3–20 completed IV sessions.
  2. **Low-IV rebound**: `rank <= lowRankThreshold` **and** current IV at least the configured absolute volatility **points** *above* the minimum over those sessions.
  3. Otherwise, `NO_SIGNAL` — especially if IV is simply high/low but has not visibly reversed.

For the synthetic deterministic tests: historic verified IV min 12%, max 44%; current 38% gives IV Rank 81.25%, and a 43% recent high gives a 5-point decline. A separate low-IV rebound fixture uses a recent 12% trough and current 17% reading. These are **illustrative unit-test fixtures**, not real NSE measurements.

## Original-market provenance and fail-closed controls

- Separate approved rights to NIFTY index history, original listed option-chain input, genuine historical option IV, and true **constant-tenor normalization**. No license is inferred from a credentials setting or a functioning LTP feed.
- An original source-provider label is required consistently for the complete index and IV series as well as the latest quote. Historical daily NIFTY candles must have positive and coherent OHLC prices, nonnegative index volume where available, and the correct 15:30 IST close instant.
- Historical IV and price observation sessions must be strictly increasing and align session by session, not reconstructed using a different publication time. `firstKnownAt` for prices and IV must be after the actual exchange close, no later than a configured 36-hour publication window, and **never in the future relative to the research evaluation time**.
- Current constant-30-day ATM source IV must be within the configured maximum real-time quote age (default unit test: 180 seconds), exchange time during supported NSE hours, source independently verified, and known **after** the latest historical completed observations. Index session coverage, maximum age and maximum calendar span must pass together.
- Repeated, missing, wrongly tenored, synthetic, out-of-range or cross-provider marks fail closed with explicit reason codes. A historical range too narrow to support a meaningful rank results in `NO_SIGNAL` instead of dividing by zero.

**Limits:** This is a simple **extremes-plus-reversal** descriptive heuristic, not a time-series forecasting model. A 52-week IV rank calculated from 252 historical observations is only comparable if the interpolation, session calendar, implied-volatility conventions and market data source are truly consistent. Implied volatility may persist at extremes, and high IV alone does **not** imply an economic volatility-risk-premium edge. Event and regime effects, abrupt gaps, skew, smile, volatility-of-volatility, bid/ask costs and actual tradable option mapping are not modeled.

## Test evidence, non-trading guarantee, blockers

14 Java/JUnit test cases cover high-IV pullback with correct 81.25% ranking, low-IV rebound with correct 15.625% ranking, high/low extremes without actual reversal, four independent research rights, different historical session/provider mismatch, genuine 30-day tenor/units, publication lookahead and duplicate-session exclusion, minimum 200-session evidence, current source freshness/method and timestamp, non-degenerate 52-week range, OHLC coherence/configuration validation, rank clamp and deterministic no-order invariants.

Every returned decision — including `RESEARCH_CANDIDATE` — sets `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`. No market-direction recommendation or short-option order is produced.

**Still missing:** licensed original source 30-day normalized NIFTY ATM implied-volatility history covering actual sessions, separately licensed original listed-option chain and true interpolation provenance, official NSE market calendar/holiday data and consistent historical OHLC, IV surface/term structure verification, independent original-as-of out-of-sample and walk-forward evidence by volatility regime, real brokerage/fees/slippage/margin/settlement and strategy-to-option risk mapping, accepted PAPER ledger/UI and separately approved LIVE options rights with kill switches. Catalog stays `DATA_BLOCKED`.

Related: [STR-200 Diagonal Spread](STR-200-diagonal-spread-research.md).
