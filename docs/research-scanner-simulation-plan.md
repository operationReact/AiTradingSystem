# Research Scanner and Scenario Simulation Implementation Design

## Goal

Extend SignalForge with a deterministic research pipeline inspired by reusable ideas from `Jakeschincariol/day-trader-skill` without importing its Claude-skill runtime, Yahoo Finance dependency, Alpaca integration, or US-market assumptions.

The authoritative execution path remains unchanged:

`stored market data -> deterministic strategy/scanner -> deterministic risk -> PAPER execution`

AI remains advisory for news/research/explanations and cannot override risk or submit orders.

## Architectural constraints

1. Java 21 + Spring Boot remain the backend platform.
2. PostgreSQL persisted candles remain the source of truth for replayable research.
3. Angel One SmartAPI remains the market-data/broker adapter boundary; research code must not call broker endpoints directly.
4. Scanner and simulation components are pure/deterministic for a fixed candle snapshot and configuration.
5. Only completed candles may be evaluated. Future/stale-candle safeguards already used by `StrategyService` remain mandatory.
6. Scanner output is a `candidate`, not an executable order.
7. `RiskPolicy` remains the final independent deterministic gate before any PAPER execution.
8. LIVE order placement remains disabled/out of scope.
9. No external LLM response may mutate scanner calculations, simulation probabilities, position size, stop, target, or risk decisions.

## Target pipeline

```text
Instrument catalogue
      |
Persisted NSE/BSE candles
      |
ScannerEngine
  |-- Volume breakout
  |-- Moving-average reclaim
  |-- Opening-range breakout (phase 2)
  |-- VWAP momentum (phase 2)
  |-- RSI reversal (phase 2)
      |
Ranked ScanCandidate(s)
      |
Historical validation / backtest
      |
ScenarioSimulationService
      |
ResearchResult
      |
RiskPolicy (only when user explicitly creates a paper order)
      |
PaperTradingService
```

## Phase 1 — deterministic scanner foundation

Deliverables:

- `scanner` package with a small strategy SPI.
- immutable `ScanContext` and `ScanCandidate` records.
- `ScannerEngine` that evaluates registered strategies against one bounded chronological candle snapshot.
- initial `VOLUME_BREAKOUT_V1` and `MA_RECLAIM_V1` strategies.
- deterministic indicator helpers with no network/database side effects.
- unit tests proving determinism, insufficient-history behavior, future-candle rejection, and positive candidate generation.

Phase 1 intentionally does not add database migrations or execution wiring.

## Phase 2 — NSE/BSE universe scanner

Add a bounded orchestration service over the active instrument catalogue:

- default universe: active NSE cash equities; BSE opt-in.
- filters: minimum stored-history count, minimum average traded value, stale-data rejection, supported interval.
- bounded maximum instruments per scan and bounded candles per instrument.
- scanner pagination/batching to avoid loading the full catalogue into memory.
- strategies: ORB, VWAP momentum, RSI reversal.
- ranking score built only from deterministic normalized strategy metrics.

Persist a scan run with provenance:

- run id, requested universe, interval, configuration version.
- candle snapshot cutoff time.
- instruments attempted/succeeded/skipped.
- candidate strategy, score, entry reference, stop suggestion, target suggestion, reasons.

No candidate is automatically executed.

## Phase 3 — scenario simulation / “Time Machine”

Introduce `research.simulation` with an explicit request/result contract.

Inputs:

- instrument id and interval.
- candidate/strategy identity.
- entry, stop and target.
- historical lookback window.
- simulation count (bounded, default 10,000).
- deterministic random seed.

Methodology:

1. classify the current setup into a volatility/market regime.
2. construct historical return blocks from matching completed sessions.
3. use block/bootstrap sampling rather than independent random candle sampling so short-term serial dependence is not destroyed.
4. apply conservative configurable slippage and fees.
5. stop every path when target or stop is reached, or horizon expires.
6. report target-first, stop-first and neither probabilities plus expected R and distribution percentiles.

Reproducibility requirements:

- same persisted candle snapshot + config + seed => byte-equivalent numeric result after normalization.
- persist methodology version, seed, candle cutoff and sample count.

## Phase 4 — risk enhancements

Audit the external reference rules against current `RiskPolicy`. Add only rules that are missing and relevant to Indian paper trading, for example:

- minimum reward/risk threshold.
- explicit per-order notional ceiling.
- maximum trades per day.
- optional trading-session gate.
- duplicate pending exposure guard.

Existing rules remain authoritative: emergency halt, signal freshness, daily drawdown, per-trade risk budget, max open positions, symbol-held guard, cash/holdings checks, deterministic fees and slippage.

Every added rule requires boundary tests and exactly-once rejection audit coverage.

## Phase 5 — research API and UI

Backend APIs (proposed):

- `POST /api/research/scans`
- `GET /api/research/scans/{runId}`
- `POST /api/research/simulations`
- `GET /api/research/simulations/{id}`

UI:

- Research/Scanner page with strategy chips, universe filters, ranked candidates and reasons.
- candidate detail with historical performance and simulation probability bars.
- “Create paper order” is an explicit user action and routes through the existing paper/risk path.
- both approved UI experiences consume the same research APIs and preserve brand DNA.

## Phase 6 — regression fixtures and CI

Maintain small deterministic fixture families such as:

- trending bullish.
- sideways/choppy.
- gap-up breakout.
- high-volatility reversal.
- low-volume false breakout.
- future/incomplete candle rejection.

CI gates:

- scanner unit tests.
- simulation determinism tests.
- PostgreSQL integration tests for persisted run provenance.
- Flyway upgrade test for any new migrations.
- existing paper-trading/risk suites remain green.
- no LIVE broker-order endpoint introduced.

## Non-goals

- copying the external repository into this repository.
- Yahoo Finance as production market data.
- Alpaca support.
- US-specific SEC/Congress/PDT/calendar logic.
- LLM-generated BUY/SELL authority.
- autonomous live execution.

## Rollout sequence

1. Scanner foundation (this branch).
2. Universe orchestration + persisted scan runs.
3. Additional NSE/BSE strategies.
4. Historical scenario simulator.
5. Risk-rule gap additions.
6. Research UI + dual-design parity.
7. Performance tuning, fixture expansion, observability and production-readiness review.
