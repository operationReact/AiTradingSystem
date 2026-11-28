# MintTick Swing Trading — Fresh Audit & First Real Swing Strategy Batch (2026-11-28)

Owner: implementation session on branch `ccr-fb98c349-uxw5hp` (based on current repo state).
Scope: audit the 142-Swing claim, inventory the authoritative catalog, and implement the first
batch of **real** swing strategies in the existing backend engine. No `main` merge, no production
deploy, no broker/SSM change, no live orders.

> Honesty rules followed (per `AGENTS.md` and the task brief): nothing below is marked implemented
> unless there is committed Flutter/Java code and a reproducible check. GAP / UNVERIFIED / BLOCKED
> are kept distinct. Synthetic fixtures are never presented as validated market performance.

---

## 1. Fresh audit — what is actually true in the repo now

| Area | 28-Nov-2026 prior-audit claim | This session's verified finding | Evidence |
|---|---|---|---|
| Branch policy | `main` prod, `dev` integration | Confirmed. Work branch is `ccr-fb98c349-uxw5hp`; `dev` and `main`-equivalent exist on origin. | `git branch -a` |
| Swing evaluators | 142 Swing labels in fixtures, only `SMA_TREND_VOLUME_V1` implemented | **Partly corrected.** Before this session: exactly **one** daily trend/volume evaluator (`SMA_TREND_VOLUME_V1`, `StrategyService`) plus **5 INTRADAY** scanner strategies (`MA_RECLAIM_V1`, `ORB`, `RSI_REVERSAL_V1`, `VOLUME_BREAKOUT`, `VWAP_MOMENTUM`). **Zero** strategies carried `TradingStyle.SWING`. No committed enumeration of 142 Swing strategies exists in the repo. | `backend/.../scanner/*.java`, `grep TradingStyle.` |
| "142 Swing" catalog | assumed | **UNSUBSTANTIATED by any committed source.** The canonical MintTick Mind master plan declares **232 strategy templates in 20 families** (18 models), explicitly "a *design inventory* … NOT 232 executed live strategies." The enumerated registry it cites (`MODELS_AND_STRATEGIES.md`, "every named strategy ID") is **not committed** to this repo and is not a standalone searchable Notion page. The number **142** appears in neither the canonical plan nor repo fixtures. | Notion canonical plan `41336a82…`; repo grep |
| `market_candle` rows | 0 rows; 7 backfill runs failed | **BLOCKED / UNVERIFIED this session.** AWS credentials in this environment are expired; RDS/EC2/ECS could not be queried. The prior snapshot is recorded as last-known, **not** re-asserted as current. | `sts:GetCallerIdentity` → expired-credential error |
| Golden-source identity / session / corporate-action / backfill tables | empty | **BLOCKED / UNVERIFIED this session** (same reason). Schemas exist as Flyway migrations `V77–V82`, `V49`, `V8`, `V14–V16`. | `backend/.../db/migration/` |
| Deployed backend mode | PAPER-only | **BLOCKED / UNVERIFIED this session.** Cannot reach the runtime to confirm current mode. | — |

**Net:** the strategy-code reality was verifiable and is now improved (see §3). The data/runtime
claims require live AWS access that this environment does not currently have; they are recorded as
BLOCKED, not fabricated.

---

## 2. Authoritative catalog — what the 142 should be measured against

- Canonical product/plan source: **MintTick Mind Complete Implementation Master Plan** — *42 screens,
  18 models, **232 strategy templates in 20 families***. These are a design inventory, not deployed
  algorithms.
- The repo contains **43** committed F&O/options strategy specs (`docs/strategies/STR-170…212`), of
  which Notion traceability claims 3 have research evaluators (STR-170/171/172). These are
  options/futures, **not** cash-equity swing.
- There is **no committed list of 142 swing-tagged equity strategies.** Before any "142/142"
  reporting is possible, the authoritative enumerated catalog (`MODELS_AND_STRATEGIES.md` or an
  equivalent committed registry) must be imported into the repo. **Recommended next step:** commit
  that registry as machine-readable JSON so status can be tracked per catalog ID.

Until then, "142" cannot be honestly used as a denominator. This session measures against the real
engine and delivers the first batch of genuinely implemented swing algorithms.

---

## 3. What was implemented this session (real code + passing checks)

A versioned **swing-strategy family** was added to the existing deterministic scanner engine
(`in.aitradingsystem.scanner`). Each entry is a distinct `ScannerStrategy` `@Component`, long-only,
daily (`ONE_DAY`) only, auto-registered in `StrategyRegistry`, and bridged into the backtest SPI via
the existing `ScannerBacktestAdapter` (point-in-time `BacktestContext`, future candles rejected).

| # | Code (versioned) | Algorithm (unique) | Min lookback (bars) | Entry trigger | Stop | Target |
|---|---|---|---|---|---|---|
| 1 | `SWING_DONCHIAN_BREAKOUT_V1` | 20-day Donchian breakout + 50-SMA trend filter | 51 | close > prior-20-day high AND close > SMA50 | lowest low (10) | 2R |
| 2 | `SWING_EMA_PULLBACK_V1` | 20/50-EMA pullback-continuation | 52 | EMA20>EMA50; prior low ≤ EMA20; close reclaims EMA20 & > prior close | min(last 2 lows) | 2R |
| 3 | `SWING_RSI2_MEANREV_V1` | Connors RSI(2) mean reversion in uptrend | 201 | close>SMA200; prior RSI(2)<10; close turns up | lowest low (5) | 2R |
| 4 | `SWING_MACD_CROSS_V1` | MACD(12,26,9) bullish cross above zero | 60 | line crosses above signal AND line>0 | lowest low (10) | 2R |
| 5 | `SWING_BOLLINGER_REVERSION_V1` | Lower-band re-entry in uptrend | 101 | close>SMA100; prior close < lower band; close re-enters band & > prior | lowest low (5) | middle band / 2R |
| 6 | `SWING_NEW_HIGH_VOLUME_V1` | 60-day high + volume expansion | 62 | close ≥ 60-day high AND vol ≥ 1.5× avg(20) | lowest low (10) | 2R |
| 7 | `SWING_GOLDEN_REGIME_PULLBACK_V1` | 50/200 golden-cross regime pullback | 201 | SMA50>SMA200; low ≤ SMA50; bullish close back above SMA50 | latest low | 2R |
| 8 | `SWING_ATR_TREND_V1` | ATR-stop trend continuation | 71 | close>rising SMA50 & >SMA20 & > prior close | close − 2·ATR(14) | 2R |

Position sizing is intentionally **not** inside the strategy: candidates are research-only
(`RESEARCH_CANDIDATE_ONLY`), never orders. Sizing/fees/slippage are applied by the downstream
backtest/paper layer against the user's configurable defaults (₹30,000 capital, moderate risk,
5–15 trading-day hold). These defaults are documented, not hard-coded limits.

**New source files**
- `backend/src/main/java/in/aitradingsystem/scanner/SwingIndicators.java` (EMA, Wilder RSI/ATR, SMA, stdDev, MACD line/signal, Donchian)
- `backend/src/main/java/in/aitradingsystem/scanner/SwingSupport.java` (daily-only guard, validated long-candidate builder, SWING descriptor factory)
- 8 strategy classes `Swing*Strategy.java`

**New test** `backend/src/test/java/in/aitradingsystem/scanner/SwingStrategiesTest.java` — 11 tests, all green:
- one positive-trigger test per strategy (deterministic, stop < ref < target, research-only reason),
- daily-only interval guard (inert on intraday) + fail-closed on a flat series for all 8,
- duplicate-code registry check,
- **backtest-SPI proof**: `SwingDonchianBreakoutStrategy` triggers through `ScannerBacktestAdapter` with a no-lookahead `BacktestContext`.

```
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0  — SwingStrategiesTest
```
No regressions in existing scanner suites (`StrategyRegistryTest`, `ExtendedScannerStrategiesTest`,
`ScannerEngineTest`, `ScannerEngineOutcomesTest`, `StrategyHardeningFixtureMatrixTest` — all green).

---

## 4. API / UI wiring status (honest)

- ✅ **Discoverable via API now:** `GET /api/research/strategies` (`ResearchStrategyController`) returns
  all `StrategyRegistry` descriptors, so the 8 swing strategies appear automatically with code,
  version, `tradingStyle=SWING`, supported intervals and min candles.
- ✅ **Backtest SPI:** implement `BacktestStrategy` via `ScannerBacktestAdapter`; no-lookahead enforced.
- ⚠️ **GAP — live daily scan loop:** `ResearchScanService.run()` explicitly rejects `ONE_DAY`
  ("Intraday research scans do not support ONE_DAY") and uses an intraday staleness window. A
  dedicated daily-swing scan entrypoint (or relaxing that guard with a daily staleness window) is
  required before these strategies produce candidates in the live research pipeline.
- ⚠️ **GAP — realistic-cost engine:** `SmaBacktestEngine` is SMA-specific; it does not yet drive an
  arbitrary `BacktestStrategy`. A generic strategy-driven backtest runner (fees, taxes, slippage,
  OOS/walk-forward) is required to produce net-of-cost performance evidence for the swing family.
- ⚠️ **GAP — MintTick Mind UI status surface:** the per-strategy status ladder (Catalogued →
  Implemented → Data-ready → Backtested → Paper-ready → Paper-running) is not yet rendered in the
  Flutter client for the swing family.

---

## 5. Per-strategy data readiness

All 8 strategies depend only on **validated daily OHLCV** for the eligible cash-equity universe,
plus split/dividend-adjusted closes and a trading-calendar for correct bar counts. None require
options chains, fundamentals or news.

| Requirement | State this session |
|---|---|
| Daily OHLCV per symbol (range, bar count, gaps, duplicates, staleness) | **BLOCKED / UNVERIFIED** — needs live DB (`market_candle`, quality tables). AWS creds expired. |
| Split/dividend adjustment | **UNVERIFIED** — corporate-action tables (`V77–V82`) schema present; population not verifiable this session. |
| Instrument/listing identity & as-of universe (survivorship) | **UNVERIFIED** — golden-source identity schema present; population not verifiable. |
| Provenance / permitted use / dataset version | **UNVERIFIED** — do **not** promote the private archive to canonical data without the controlled ingestion/validation path. |
| No lookahead / survivorship leakage | ✅ enforced structurally: `BacktestContext` point-in-time view + chronological `ScanContext`; strategies read only completed bars ≤ latest. |

**Per-symbol readiness is therefore BLOCKED pending data verification.** No symbol can be called
data-ready until the daily archive is validated and promoted through the controlled path.

---

## 6. Paper-ready vs blocked

- **Paper-ready now:** none of the 8 can be truthfully called paper-ready, because (a) the live daily
  scan loop does not yet run `ONE_DAY`, and (b) data readiness is unverified. They ARE
  implementation-complete and test-verified as deterministic research strategies.
- **Blocked on:** daily data validation/promotion (DB access); a daily-swing scan entrypoint; a
  generic cost-aware backtest runner for net-of-cost OOS/walk-forward evidence.

## 7. Remaining requirements for a separate live-readiness decision

1. Import the authoritative enumerated catalog (142/232 IDs) as committed machine-readable JSON.
2. Verify + promote validated daily OHLCV with provenance, adjustments, identity, calendar coverage.
3. Add a daily-swing scan entrypoint and a generic strategy-driven, cost-aware backtest/walk-forward
   runner; record data-version + strategy-version + params + period for every result.
4. Surface the per-strategy status ladder and explainability in MintTick Mind (Flutter web/Android/iOS).
5. Keep paper/live paths separate; add configurable risk limits + kill switch; never let an LLM
   explanation create/approve an order. Live activation remains separately gated (unchanged).

## 8. Validation evidence / failed gates

- Ran: targeted scanner + backtest-SPI unit suites via `mvn test -Dtest=…` on JDK 21 / Spring Boot 4.1.2. All green (see §3).
- **Not run:** full `mvn test` (the `@SpringBootTest` integration tests require Testcontainers/Docker + Postgres, unavailable in this environment) and any Flutter web/Android/iOS build. These are **not** claimed as passed.
