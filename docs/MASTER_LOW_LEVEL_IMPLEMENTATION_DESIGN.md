# AI Trading System — Master Low-Level Implementation Design

> **Status:** AUTHORITATIVE TECHNICAL EXECUTION SOURCE  
> **Scope authority:** `docs/MASTER_IMPLEMENTATION_PLAN.md`  
> **Repository:** `operationReact/AiTradingSystem`  
> **Integration branch:** `dev`  
> **Last reconciled:** 2026-10-28  
> **Purpose:** Concrete implementation blueprint from the current repository state through production PAPER/research launch and the separately gated LIVE pilot.

---

# 1. How this document must be used

This is not an aspirational roadmap. It is the implementation contract for engineering work.

Every future implementation PR must reference at least one work-item ID in this document, for example `P04-W03`. If implementation discovers that a table, API, class, state transition, or UI contract in this document is wrong, the PR must update this file and `MASTER_IMPLEMENTATION_PLAN.md` in the same change.

Historical issue/PR phase numbers are supporting history only. The authoritative phase numbers are Phase 0–15 here and in `MASTER_IMPLEMENTATION_PLAN.md`.

## 1.1 Non-negotiable product rules

1. Product scope for V1 is Indian NSE/BSE cash-equity research + PAPER execution.
2. `PAPER` is the default execution mode.
3. LIVE broker submission remains technically disabled until Phase 15 activation criteria pass.
4. AI/news is research context only. It cannot create an executable order, approve a hard risk rejection, change risk limits, activate LIVE, or bypass an emergency stop.
5. Deterministic risk evaluation is mandatory immediately before execution.
6. Money, price, fee, risk and P&L calculations use `BigDecimal`; persisted numeric fields use `NUMERIC`, never floating-point.
7. Persist absolute timestamps as `TIMESTAMPTZ`; Java domain uses `Instant`. NSE/BSE session interpretation uses `Asia/Kolkata` only at domain boundaries.
8. Every execution-impacting workflow carries a `correlationId`; mutating idempotent endpoints additionally carry an idempotency key or immutable intent ID.
9. Pro Terminal and Clear Invest use identical APIs, authorization, data, risk rules, React Query cache and business state. They differ in composition/presentation only.
10. No service worker/offline component may queue or replay order-submission, approval, emergency-stop, execution-mode or other trading mutations.
11. Flyway migrations are forward-only. Use the next available migration number at implementation time; do not reserve future version numbers in this document.
12. AWS account activation is not a blocker for application phases. Terraform/code/security work proceeds independently until apply credentials exist.

---

# 2. Current code that must be extended, not replaced

## Backend roots

```text
in.aitradingsystem
├── api
├── audit
├── backtest
├── common
├── config
├── domain
├── instrument
├── marketdata
├── news
├── paper
├── provider/angelone
├── research/simulation
├── risk
├── scanner
├── security
└── strategy
```

New roots are allowed only for genuinely new domains:

```text
approval
analytics
broker
notification
portfolio
reconciliation
```

## Existing database baseline

Existing tables include, among others:

- `paper_account`
- `trading_control`
- `paper_order`
- `risk_decision`
- `paper_position`
- `paper_fill`
- `paper_cash_entry`
- `audit_event`
- instrument-master/version/import tables
- `market_candle`
- `candle_backfill_run`
- `candle_backfill_checkpoint`
- `strategy_signal`
- `paper_backtest_run`
- `paper_backtest_trade`
- `paper_backtest_validation`
- provenance-aware news tables
- `research_scan_run`
- `research_scan_candidate`

Do not create duplicate account/order/fill/candle/signal/scanner tables where forward-compatible ALTER/new-child-table migrations can extend these.

## Existing frontend roots

```text
frontend/src
├── App.jsx
├── components/
├── context/AppContext.jsx
├── hooks/useApi.js
├── lib/
└── pages/
```

Existing routes:

- `/` Overview
- `/signals`
- `/trades`
- `/risk`
- `/instruments`
- `/market-data`
- `/backtesting`

Existing global experience values are `pro-terminal` and `clear-invest` in `AppContext`.

---

# 3. Cross-cutting technical contracts

## 3.1 API response/error contract

All new controllers must converge on a single `ApiError` record:

```java
public record ApiError(
    String code,
    String message,
    String correlationId,
    Instant timestamp,
    Map<String, Object> details
) {}
```

Create/extend `GlobalExceptionHandler` to map:

- validation -> `400`
- authentication -> `401`
- authorization -> `403`
- not found -> `404`
- idempotency conflict -> `409`
- invalid state transition -> `409`
- rate/provider unavailable -> `503`
- unexpected exception -> `500` without leaking credentials/provider payloads.

Do not expose raw exception stack traces or broker secrets.

## 3.2 Pagination contract

Use `page >= 0`, `1 <= size <= 200` for ordinary operational lists. Research/history-specific APIs may have their own tighter bounds. Response:

```java
public record PageResponse<T>(List<T> items, int page, int size, long total) {}
```

Cursor pagination can be introduced only where timestamp/ID ordering materially improves scale; do not mix styles on the same endpoint.

## 3.3 Audit contract

`AuditService.record(eventType, entityType, entityId, details)` remains the canonical write entry point until Phase 10 adds authenticated actor identity. Every mutation below explicitly identifies required audit events.

Required detail fields for execution-impacting events:

- `correlationId`
- `actorId` when available
- `executionMode`
- `entityVersion`/policy version when relevant
- before/after state when appropriate
- reason codes, not only prose.

## 3.4 Database transaction rules

- Order/risk/fill/cash/position mutation is one transaction.
- Approval decision and order-intent transition are transactional.
- External broker calls are never executed while holding a long database transaction/row lock. Use durable state-before-call, call broker, then persist result in a second transaction.
- Scanner/backtest/simulation runs persist status transitions (`CREATED/RUNNING/SUCCEEDED/FAILED`) so restarts are visible.
- Reconciliation writes differences; it does not silently mutate historical audit/fill records.

## 3.5 Query/cache keys

Extend `frontend/src/hooks/useApi.js` `keys` object; do not scatter ad-hoc query keys throughout components. New domain roots:

```js
research: ['research', ...]
approvals: ['approvals', ...]
portfolioAnalytics: ['portfolio-analytics', ...]
notifications: ['notifications', ...]
operations: ['operations', ...]
audit: ['audit', ...]
settings: ['settings', ...]
```

Mutations must invalidate only affected root keys, not the entire cache.

---

# PHASE 0 — Architecture, safety, CI and engineering baseline

**Status:** DONE for baseline; governance remains mandatory.

## P00-W01 — Correlation/error normalization

### Backend

Existing `CorrelationIdFilter` remains canonical. Add/confirm:

- incoming `X-Correlation-Id` accepted only if bounded/safe (`<=64`, `[A-Za-z0-9._-]`), otherwise generate UUID;
- response always includes `X-Correlation-Id`;
- MDC cleanup in `finally`;
- `ApiError` uses current MDC correlation ID.

### Tests

`CorrelationIdFilterTest`
- generates when absent;
- preserves valid caller ID;
- rejects oversized/invalid ID;
- clears MDC between requests.

`GlobalExceptionHandlerTest`
- no stack trace/secret leakage;
- correct HTTP mappings.

## P00-W02 — CI contract

`.github/workflows/phase0-validation.yml` remains required for PRs into `dev` and pushes to `dev`.

Required jobs:

1. backend Java 21 tests with PostgreSQL/Testcontainers;
2. frontend Node 20 install + mobile metadata validation + production build;
3. backend Docker build;
4. frontend Docker build;
5. `terraform fmt -check` + `terraform validate` for both infra stacks;
6. later phases add security/dependency/E2E jobs without removing the above.

### Exit evidence

No phase is marked complete unless latest `dev` run is green.

---

# PHASE 1 — Instrument master and market-data production readiness

**Status:** PARTIAL. Existing instrument/version/backfill/candle pipeline is reused.

## P01-W01 — Shared market session/calendar service

### New classes

`marketdata/MarketSessionCalendar.java`

```java
boolean isTradingDay(LocalDate date);
MarketSession session(LocalDate date);
boolean isInsideCashSession(Instant instant);
Optional<Instant> previousExpectedCandle(Instant candle, HistoricalCandleInterval interval);
List<ExpectedCandleSlot> expectedSlots(LocalDate date, HistoricalCandleInterval interval);
```

`marketdata/MarketSession.java`

```java
record MarketSession(LocalDate tradeDate, ZonedDateTime open, ZonedDateTime close, boolean tradingDay) {}
```

`marketdata/IndianCashMarketCalendar.java`
- timezone fixed to `Asia/Kolkata`;
- regular session baseline 09:15–15:30;
- weekends closed;
- holiday override supplied by database/config, not hard-coded forever.

### New table `market_holiday`

- `trade_date DATE PRIMARY KEY`
- `market VARCHAR(16) NOT NULL DEFAULT 'NSE_CASH'`
- `holiday_name VARCHAR(120) NOT NULL`
- `source VARCHAR(80) NOT NULL`
- `source_as_of DATE NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`

Index only PK needed initially.

### API

`GET /api/market-data/calendar?from=YYYY-MM-DD&to=YYYY-MM-DD`
- max 370 days;
- returns sessions/holidays for operational visibility.

## P01-W02 — Candle quality and gap detection

### New table `market_data_quality`

Columns:
- `id BIGSERIAL PK`
- `instrument_id BIGINT NOT NULL`
- `interval VARCHAR(20) NOT NULL`
- `window_from TIMESTAMPTZ NOT NULL`
- `window_to TIMESTAMPTZ NOT NULL`
- `expected_candles INTEGER NOT NULL`
- `actual_candles INTEGER NOT NULL`
- `missing_candles INTEGER NOT NULL`
- `duplicate_candles INTEGER NOT NULL DEFAULT 0`
- `zero_volume_candles INTEGER NOT NULL DEFAULT 0`
- `status VARCHAR(16) CHECK IN ('HEALTHY','DEGRADED','INCOMPLETE','FAILED')`
- `gap_ranges JSONB NOT NULL DEFAULT '[]'`
- `evaluated_at TIMESTAMPTZ NOT NULL`
- unique `(instrument_id, interval, window_from, window_to)`

Indexes:
- `(instrument_id, interval, evaluated_at DESC)`
- `(status, evaluated_at DESC)`

### New classes

`CandleGapDetector`
- input: chronologically ordered candles + expected slots;
- output: immutable `MarketDataQualityResult`;
- ignore non-trading-day gaps;
- detect missing expected candle timestamps;
- detect duplicates defensively even though DB unique constraint exists;
- never invent/interpolate candle values.

`MarketDataQualityService`
- `evaluate(instrumentId, interval, from, to)`
- `latest(instrumentId, interval)`
- `eligibleForResearch(...)` -> fail closed when missing percentage exceeds configured threshold.

`MarketDataQualityRepository`
- `upsertQualityResult`
- `findLatest`
- `findDegraded(limit)`.

### Existing integration

`ResearchScanService`, `StrategyService`, backtest and scenario orchestration must call the quality gate before evaluating a production candidate. Unit tests may use explicit fixture bypass.

## P01-W03 — Backfill reconciliation

### New service

`MarketDataReconciliationService`

```java
ReconciliationResult reconcile(long instrumentId, String interval, Instant from, Instant to);
List<GapWindow> findMissingWindows(...);
```

It does not fetch automatically unless `marketdata.reconciliation.auto-backfill=true`; default `false` until provider verification.

### Scheduler

`MarketDataReconciliationScheduler`
- disabled by default locally;
- production configurable cron after market close;
- bounded max instruments/run;
- uses distributed-safe database claim if multiple app instances are possible.

### Operational API

- `POST /api/market-data/quality/evaluate`
- `GET /api/market-data/quality?status=&limit=`
- `POST /api/market-data/reconcile` (operator only; no broker order implications)

## P01-W04 — Angel One throttling/provider metrics

### New/extended classes

`provider/angelone/AngelOneRateLimiter`
- token-bucket/sliding-window based on verified current provider limits;
- config properties only after official docs review;
- rate values must not be guessed into production defaults.

`AngelOneCallMetrics`
- request count;
- response class;
- latency;
- throttled count;
- retry count;
- auth/session failures.

`AngelOneRetryPolicy`
- retry only safe read requests;
- exponential backoff + jitter;
- max attempts bounded;
- do not retry validation/auth failures blindly.

## P01-W05 — UI market-data operations

Extend `/market-data` page, preserving existing backfill UI.

New components:
- `MarketDataQualityCard.jsx`
- `GapRangeTable.jsx`
- `BackfillRunDetailDrawer.jsx`
- `ProviderHealthBadge.jsx`
- `MarketCalendarPanel.jsx`

New hooks:
- `useMarketDataQuality(params)`
- `useEvaluateMarketDataQuality()`
- `useMarketCalendar(range)`
- `useReconcileMarketData()`

Both designs render the same data:
- Pro Terminal: dense status table + inline quality badges;
- Clear Invest: guided health cards + human-readable gap explanation.

## Phase 1 tests

- `IndianCashMarketCalendarTest`
- `CandleGapDetectorTest`
- `MarketDataQualityRepositoryIT`
- `MarketDataQualityApiIT`
- `MarketDataReconciliationServiceTest`
- existing historical ingestion/backfill tests remain green.

### Phase 1 exit gate

Instrument identity + candle history for configured production universe can be quality-scored, gaps identified, provider calls bounded, and recovery initiated without direct DB access.

---

# PHASE 2 — Deterministic strategy and scanner suite

**Status:** PARTIAL. Existing scanner classes remain the implementation base.

## P02-W01 — Strategy metadata registry

### New interface

`scanner/StrategyDescriptorProvider.java`

```java
StrategyDescriptor descriptor();
```

Extend `ScannerStrategy` to expose metadata without changing deterministic `evaluate` semantics.

`StrategyDescriptor` fields:
- `code`
- `version`
- `displayName`
- supported intervals;
- minimum candles;
- `TradingStyle` (`INTRADAY`, `SWING`);
- session requirement;
- data-quality minimum;
- long/short capability (V1 cash-equity remains long-only unless explicitly implemented).

### Registry

`StrategyRegistry`

```java
List<ScannerStrategy> enabledStrategies();
Optional<ScannerStrategy> require(String code);
List<StrategyDescriptor> descriptors();
```

Spring injects implementations; registry sorts by stable strategy code to preserve deterministic iteration.

## P02-W02 — Persisted strategy configuration/versioning

### New table `strategy_config`

- `id UUID PK`
- `strategy_code VARCHAR(80) NOT NULL`
- `config_version INTEGER NOT NULL`
- `enabled BOOLEAN NOT NULL`
- `config_json JSONB NOT NULL DEFAULT '{}'`
- `effective_from TIMESTAMPTZ NOT NULL`
- `effective_to TIMESTAMPTZ NULL`
- `created_by VARCHAR(120) NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`
- unique `(strategy_code, config_version)`
- partial unique index allowing one active row per `strategy_code` where `effective_to IS NULL`.

Do not allow arbitrary formulas/scripts in `config_json`. Only validated typed keys declared by each strategy.

### Classes

`StrategyConfigRepository`
`StrategyConfigService`
`StrategyConfigSnapshot`

The snapshot ID/version is attached to future scan/backtest/simulation provenance.

## P02-W03 — Candidate identity/deduplication

Extend `research_scan_candidate` with forward migration:

- `candidate_fingerprint CHAR(64)`
- `strategy_config_version INTEGER`
- `catalogue_version UUID`
- `data_cutoff_at TIMESTAMPTZ`

Fingerprint canonical input:
`instrumentId|interval|strategy|strategyVersion|signalCandleTime|entry|stop|target`.

Unique `(scan_run_id, candidate_fingerprint)`.

`CandidateFingerprint` utility must canonicalize `BigDecimal.stripTrailingZeros().toPlainString()` and UTC instant strings.

## P02-W04 — Strategy hardening

For every existing strategy:

- `VolumeBreakoutStrategy`
- `MovingAverageReclaimStrategy`
- `OpeningRangeBreakoutStrategy`
- `VwapMomentumStrategy`
- `RsiReversalStrategy`
- existing `StrategyService` SMA trend/volume path

add:

1. positive fixture;
2. negative fixture;
3. exact-boundary fixture;
4. insufficient-history fixture;
5. future/stale candle fixture;
6. duplicate/out-of-order candle rejection fixture;
7. deterministic replay fixture.

Create `CandleSeriesValidator` shared by scanner/backtest/research:
- strict ascending candle times;
- no future candle relative to cutoff;
- interval continuity quality delegated to Phase 1;
- non-empty/positive OHLC already database-constrained but checked for unit fixtures.

## P02-W05 — Scanner runtime/performance

`ResearchScanService` changes:
- registry-driven strategies instead of hard-coded list;
- max universe still hard capped at 500 unless master plan changes;
- per-instrument failures captured as scan diagnostics instead of failing entire run when safe;
- scan-run status becomes `RUNNING/SUCCEEDED/PARTIAL/FAILED`.

Add child table `research_scan_diagnostic`:
- `id BIGSERIAL PK`
- `scan_run_id UUID FK`
- `instrument_id BIGINT NULL`
- `strategy VARCHAR(80) NULL`
- `severity VARCHAR(12)`
- `code VARCHAR(80)`
- `message VARCHAR(300)`
- `created_at TIMESTAMPTZ`.

## P02-W06 — Strategy/scanner APIs

- `GET /api/research/strategies`
- `GET /api/research/strategies/{code}`
- `PUT /api/research/strategies/{code}/config` operator/admin only once Phase 10 auth exists; before then existing operator API-key gate.
- existing `POST /api/research/scans`
- existing `GET /api/research/scans/{id}`
- existing `GET /api/research/scans/{id}/candidates`
- add `GET /api/research/scans/{id}/diagnostics`.

## P02-W07 — UI scanner foundation

New route `/research` initially hosts scanner list/candidates.

Components:
- `ResearchScanLauncher.jsx`
- `StrategySelector.jsx`
- `ScanRunStatus.jsx`
- `CandidateTable.jsx`
- `CandidateScoreBadge.jsx`
- `CandidateReasonList.jsx`
- `ResearchDiagnosticsDrawer.jsx`

Hooks:
- `useResearchStrategies()`
- `useResearchScans(params)`
- `useResearchScan(id)`
- `useResearchCandidates(id, limit)`
- `useStartResearchScan()`

No UI control may convert a candidate directly into an executed order. Candidate detail can open an order-review flow only after Phase 5/7 rules.

### Phase 2 exit gate

Every enabled strategy is versioned/configured, deterministic, fixture-covered, scanner-bounded and provenance-linked.

---

# PHASE 3 — Backtesting and statistical validation

**Status:** PARTIAL. Existing `paper_backtest_run/trade/validation` are extended.

## P03-W01 — Strategy-neutral backtest SPI

New interfaces:

`backtest/BacktestStrategy.java`

```java
String code();
String version();
BacktestSignal evaluate(BacktestContext context);
```

`ScannerBacktestAdapter` wraps compatible `ScannerStrategy` implementations without duplicating strategy math.

`BacktestContext` includes:
- candles up to current index only;
- current cutoff;
- config snapshot;
- account/equity state;
- no future candles.

## P03-W02 — Provenance/metrics schema extension

Alter `paper_backtest_run` add:

- `strategy_config_version INTEGER NULL`
- `methodology_version VARCHAR(80) NOT NULL DEFAULT 'BACKTEST_V1'`
- `data_cutoff_at TIMESTAMPTZ NULL`
- `source_candle_count INTEGER`
- `benchmark_return_percent NUMERIC(12,4)`
- `expectancy_r NUMERIC(14,6)`
- `average_r NUMERIC(14,6)`
- `exposure_percent NUMERIC(12,4)`
- `turnover NUMERIC(20,2)`
- `max_consecutive_losses INTEGER`
- `assumptions_json JSONB NOT NULL DEFAULT '{}'`.

Add `paper_backtest_equity_point`:
- `run_id UUID FK`
- `point_time TIMESTAMPTZ`
- `equity NUMERIC(20,2)`
- `cash NUMERIC(20,2)`
- `open_value NUMERIC(20,2)`
- primary key `(run_id, point_time)`.

## P03-W03 — Walk-forward validation

New tables:

`backtest_walk_forward_run`
- `id UUID PK`
- instrument/interval/strategy identity;
- overall from/to;
- training window days;
- validation window days;
- step days;
- status;
- created/completed/error fields.

`backtest_walk_forward_fold`
- `walk_forward_id UUID FK`
- `fold_number INTEGER`
- `training_run_id UUID FK paper_backtest_run`
- `validation_run_id UUID FK paper_backtest_run`
- date ranges;
- train return;
- validation return;
- validation max drawdown;
- primary key `(walk_forward_id, fold_number)`.

No optimization/hyperparameter search is introduced unless separately specified. Same fixed strategy config is used across folds for V1.

## P03-W04 — Leakage protections

Create `BacktestDataGuard`:
- rejects unordered candles;
- no candidate/evaluation may read candle index > current simulation index;
- entry only at next candle open where existing methodology requires it;
- session gap handling explicit;
- no use of post-cutoff corporate/news information in deterministic price strategy.

`LookAheadBiasRegressionTest` uses a fixture where a future spike would change an SMA if future data leaked; expected pre-spike decisions must remain unchanged.

## P03-W05 — APIs

Existing endpoints stay compatible. Add:

- `POST /api/backtests`
- `GET /api/backtests/{id}`
- `GET /api/backtests?instrumentId=&strategy=&page=&size=`
- `POST /api/backtests/walk-forward`
- `GET /api/backtests/walk-forward/{id}`
- `GET /api/backtests/{id}/equity`
- `GET /api/backtests/{id}/export.csv` with CSV-injection-safe escaping.

## P03-W06 — UI

Extend existing `BacktestingPage.jsx` into controller composition; extract:
- `BacktestForm.jsx`
- `BacktestMetricsGrid.jsx`
- `EquityCurveChart.jsx`
- `DrawdownChart.jsx`
- `BacktestTradeTable.jsx`
- `ValidationComparison.jsx`
- `WalkForwardPanel.jsx`
- `AssumptionsDrawer.jsx`.

Pro Terminal uses dense metric strip + charts/table; Clear Invest uses guided summary, explicit assumptions and plain-language labels.

### Phase 3 exit gate

Every enabled production strategy has repeatable leakage-safe historical validation with persisted methodology/config/data cutoff and exportable evidence.

---

# PHASE 4 — Scenario simulation and regime-aware research

**Status:** IN PROGRESS. Existing `HistoricalScenarioSimulator` and request/result/service/controller remain.

## P04-W01 — Scenario persistence

Create `research_scenario_run`:

- `id UUID PK`
- `scan_run_id UUID NULL FK research_scan_run`
- `candidate_id BIGINT NULL FK research_scan_candidate`
- `instrument_id BIGINT NOT NULL`
- `exchange VARCHAR(20)`
- `trading_symbol VARCHAR(120)`
- `interval VARCHAR(32) NOT NULL`
- `strategy VARCHAR(80) NULL`
- `strategy_config_version INTEGER NULL`
- `entry_price NUMERIC(20,6) NOT NULL`
- `stop_price NUMERIC(20,6) NOT NULL`
- `target_price NUMERIC(20,6) NOT NULL`
- `history_from TIMESTAMPTZ NOT NULL`
- `history_to TIMESTAMPTZ NOT NULL`
- `data_cutoff_at TIMESTAMPTZ NOT NULL`
- `simulation_count INTEGER NOT NULL`
- `horizon_bars INTEGER NOT NULL`
- `block_size INTEGER NOT NULL`
- `seed BIGINT NOT NULL`
- `slippage_bps INTEGER NOT NULL`
- `round_trip_cost_bps INTEGER NOT NULL`
- `methodology VARCHAR(80) NOT NULL`
- `regime VARCHAR(32) NOT NULL`
- `historical_samples INTEGER NOT NULL`
- `eligible_block_count INTEGER NOT NULL`
- target/stop/neither counts + probabilities;
- `expected_r NUMERIC(18,8)`
- `p05_r/p50_r/p95_r NUMERIC(18,8)`
- `status VARCHAR(16) CHECK IN ('CREATED','RUNNING','SUCCEEDED','FAILED')`
- error fields;
- `started_at/completed_at/created_at TIMESTAMPTZ`.

Indexes:
- `(candidate_id, created_at DESC)`
- `(instrument_id, interval, created_at DESC)`
- `(status, created_at DESC)`.

## P04-W02 — Regime model

New enum `MarketRegime { LOW_VOL, NORMAL_VOL, HIGH_VOL }`.

`RegimeClassifier`
- default window 20 completed candles;
- calculate returns using close-to-close log/simple return consistently (document method);
- realized volatility estimate;
- classify against historical 33rd/67th percentile thresholds from eligible historical blocks;
- no future observations beyond `dataCutoffAt`.

`LiquidityRegime { LOW, NORMAL, HIGH }`
- based on relative rolling median/average volume;
- used as secondary match filter, never price prediction.

`RegimeSnapshot`
- volatility metric;
- lower/upper thresholds;
- volatility regime;
- volume metric;
- volume regime;
- methodology version.

## P04-W03 — Session-safe block sampling

Replace unrestricted transition list with `HistoricalBlockSampler`.

`HistoricalBlock` contains:
- source trade date;
- start candle time;
- observations list;
- regime snapshot at block start.

Rules:
- block cannot cross `Asia/Kolkata` trade date;
- block cannot cross session close;
- block length exactly request block size;
- block cannot include candle after data cutoff;
- prefer exact volatility + volume regime match;
- fallback order: same volatility -> all eligible blocks;
- persist `eligibleBlockCount` and fallback tier.

Methodology becomes `SESSION_REGIME_BLOCK_BOOTSTRAP_V2`.

Same seed + same history/config must produce byte-for-byte-equivalent numeric result after scale normalization.

## P04-W04 — Orchestration

`ScenarioResearchOrchestrator`

```java
ResearchEvaluationResult evaluateCandidate(long candidateId, ResearchEvaluationRequest request);
```

Flow:
1. load candidate + scan provenance;
2. verify candidate still references immutable historical candle time;
3. resolve strategy/config version;
4. run/reuse backtest if requested;
5. run/reuse scenario simulation by request fingerprint;
6. persist research-evaluation link;
7. return research-only result.

Create `research_evaluation`:
- `id UUID PK`
- `candidate_id BIGINT NOT NULL`
- `backtest_run_id UUID NULL`
- `scenario_run_id UUID NOT NULL`
- `decision_label VARCHAR(24)` limited to research labels such as `FAVORABLE/MIXED/UNFAVORABLE/INSUFFICIENT_DATA`; never `EXECUTE`;
- `reason_codes JSONB`
- `created_at`.

## P04-W05 — APIs

- keep `POST /api/research/simulations` for direct expert use;
- `POST /api/research/candidates/{candidateId}/evaluate`
- `GET /api/research/candidates/{candidateId}/evaluations`
- `GET /api/research/simulations/{id}`
- `GET /api/research/simulations?candidateId=&page=&size=`.

Defaults for candidate evaluation:
- simulations 10,000;
- server-defined cost/slippage profile;
- deterministic seed derived from explicit stored seed generator/request, never hidden random replayability loss.

## P04-W06 — Tests

- `RegimeClassifierTest`
- `HistoricalBlockSamplerTest`
- `SessionBoundarySamplingTest`
- `HistoricalScenarioSimulatorV2Test`
- `ScenarioSimulationRepositoryIT`
- `ScenarioResearchOrchestratorIT`
- same-seed replay;
- exact regime fallback;
- no overnight block crossing;
- stop-first same-bar conservative behavior preserved.

## P04-W07 — UI contract (implemented visually in Phase 9)

Define now:
- `CandidateResearchDrawer`
- scenario probability chart;
- R distribution summary;
- regime chips;
- methodology/provenance drawer;
- explicit `Research only — not an order` label.

### Phase 4 exit gate

Candidate -> historical validation -> regime-aware scenario result is persisted, reproducible and linked to immutable provenance.

---

# PHASE 5 — Deterministic risk engine completion

**Status:** PARTIAL. Existing `RiskPolicy`, `RiskInput`, `RiskDecision`, `RiskReason` remain core.

## P05-W01 — Rule decomposition without behavior regression

Introduce:

```java
interface RiskRule {
    String code();
    RiskRuleResult evaluate(RiskContext context);
}
```

`RiskRuleResult`:
- code;
- `PASS`, `HARD_REJECT`, `APPROVAL_REQUIRED`;
- numeric observed value;
- numeric configured limit;
- message key.

`RiskPolicy.evaluate` becomes ordered composition of immutable rules, preserving existing outputs during migration.

Rules:
- `EmergencyStopRule`
- `SignalFreshnessRule`
- `DailyLossRule`
- `PriceLevelRule`
- `MinimumRewardRiskRule`
- `PerTradeRiskBudgetRule`
- `MaxOpenPositionsRule`
- `ExistingSymbolExposureRule`
- `CashAvailabilityRule`
- `HoldingsAvailabilityRule`
- `MaxOrderNotionalRule`
- `GrossPortfolioExposureRule`
- `MaxTradesPerDayRule`
- `MarketSessionRule`
- `SymbolConcentrationRule`
- `DuplicatePendingExposureRule`
- `MarketStateFreshnessRule`.

Hard risk rules cannot return `APPROVAL_REQUIRED` merely because policy mode is MANUAL/HYBRID.

## P05-W02 — Risk policy version persistence

Create `risk_policy_config`:
- `id UUID PK`
- `policy_version INTEGER NOT NULL UNIQUE`
- `active BOOLEAN NOT NULL`
- all typed limits as NUMERIC/INTEGER columns where stable;
- `additional_config JSONB NOT NULL DEFAULT '{}'` only for forward extension;
- `effective_from/effective_to`;
- `created_by`;
- `created_at`.

Core typed columns:
- risk per trade %;
- max daily drawdown %;
- max open positions;
- max order notional;
- max gross exposure %;
- max symbol exposure %;
- max trades/day;
- min reward:risk;
- max signal age minutes;
- market-state max age seconds;
- fee/slippage profile version.

Alter `risk_decision` add:
- `policy_config_id UUID NULL`
- `policy_snapshot JSONB NOT NULL DEFAULT '{}'`
- `result_class VARCHAR(24)`.

Create `risk_decision_rule`:
- `risk_decision_id UUID FK`
- `rule_code VARCHAR(80)`
- `outcome VARCHAR(24)`
- `observed_value NUMERIC(24,8) NULL`
- `limit_value NUMERIC(24,8) NULL`
- `details JSONB`
- primary key `(risk_decision_id, rule_code)`.

## P05-W03 — Position sizing

`PositionSizingService`

Inputs:
- capital/equity;
- entry;
- stop;
- configured risk budget;
- max notional;
- concentration/exposure remaining;
- lot size (cash equity default 1).

Output `PositionSizeSuggestion`:
- quantity;
- risk/share;
- total estimated risk;
- estimated notional;
- limiting rule code.

Quantity uses floor division and returns zero if minimum one share violates any hard limit.

Endpoint:
`POST /api/risk/position-size` — advisory only, server recalculates at actual order evaluation.

## P05-W04 — RiskContextFactory

New `RiskContextFactory` centralizes fresh state loading:
- trading control;
- account cash/equity;
- current positions;
- daily realized P&L;
- today trade count;
- pending intents;
- current market/session state;
- candidate/signal timestamp;
- active risk-policy snapshot.

No controller builds risk input manually.

## P05-W05 — Risk API/UI contract

APIs:
- `GET /api/risk/policy`
- `GET /api/risk/policies?page=&size=`
- `PUT /api/risk/policy` admin-only after Phase 10;
- existing decisions endpoint adds rule details;
- `POST /api/risk/position-size`;
- emergency stop preserved.

Audit events:
- `RISK_POLICY_CHANGED`
- `RISK_EVALUATED`
- `EMERGENCY_STOP_CHANGED`.

### Phase 5 tests

One exact-boundary test per rule, plus:
- concurrency duplicate exposure;
- missing/stale market state fail closed;
- risk snapshot immutability;
- approval cannot turn HARD_REJECT into pass.

### Phase 5 exit gate

An immutable order intent cannot move toward execution unless every hard deterministic risk rule passes against fresh state and the persisted policy snapshot is auditable.

---

# PHASE 6 — PAPER order lifecycle, accounting and portfolio engine

**Status:** PARTIAL. Existing `PaperTradingService` transaction/account lock remains until safely decomposed.

## P06-W01 — Explicit order model

Forward migration alters `paper_order` add:
- `instrument_id BIGINT NULL` (backfill from symbol/catalogue where deterministic; nullable for legacy rows);
- `exchange VARCHAR(8)`;
- `order_type VARCHAR(12) DEFAULT 'MARKET'` (`MARKET`,`LIMIT`);
- `limit_price NUMERIC(20,6) NULL`;
- `product_type VARCHAR(12) DEFAULT 'DELIVERY'` (`DELIVERY`,`INTRADAY`);
- `strategy VARCHAR(80) NULL`;
- `candidate_id BIGINT NULL`;
- `approval_id UUID NULL` added after Phase 7 table exists;
- `submitted_quantity INTEGER`;
- `filled_quantity INTEGER DEFAULT 0`;
- `average_fill_price NUMERIC(20,6) NULL`.

Expand allowed status state machine:

`NEW -> RISK_APPROVED -> APPROVAL_PENDING -> READY -> PARTIALLY_FILLED -> FILLED`

Terminal alternatives:
- `REJECTED`
- `CANCELLED`
- `EXPIRED`
- `FAILED`.

For Phase 6 PAPER before approvals exist, `RISK_APPROVED -> READY` directly.

Create `OrderStateMachine` with explicit allowed transition map. Database updates always use `WHERE id=? AND status=?` optimistic transition check.

## P06-W02 — Partial-fill-safe fill schema

Current `paper_fill.order_id` is unique and must be relaxed before partial fills.

Migration:
- drop unique constraint on `order_id`;
- add `fill_sequence INTEGER NOT NULL DEFAULT 1`;
- unique `(order_id, fill_sequence)`;
- add `execution_model_version VARCHAR(40)`;
- add `liquidity_assumption VARCHAR(40)`.

`PaperFillRepository.appendFill(orderId, fillSequence, ...)`.

V1 PAPER default may still produce one fill per order; schema must no longer prevent future deterministic partial fill simulation.

## P06-W03 — Position model

Alter `paper_position`:
- add `instrument_id BIGINT`;
- `exchange VARCHAR(8)`;
- `product_type VARCHAR(12)`;
- rename semantics by adding `average_price NUMERIC(20,6)`; keep legacy `cost_basis` until migration/backfill complete;
- `realized_pnl NUMERIC(20,2) DEFAULT 0`;
- unique target becomes `(instrument_id, product_type)` once legacy data converted.

Create `PositionAccountingService`:
- weighted-average cost for cash positions;
- deterministic realized P&L on SELL;
- no short positions in V1 cash-equity unless master scope changes;
- intraday positions handled separately from delivery.

## P06-W04 — Portfolio snapshots

Create `paper_portfolio_snapshot`:
- `id BIGSERIAL PK`
- `snapshot_at TIMESTAMPTZ`
- `cash NUMERIC(20,2)`
- `market_value NUMERIC(20,2)`
- `equity NUMERIC(20,2)`
- `realized_pnl_day NUMERIC(20,2)`
- `unrealized_pnl NUMERIC(20,2)`
- `gross_exposure NUMERIC(20,2)`
- `open_positions INTEGER`
- unique `snapshot_at` or controlled interval key.

`PortfolioValuationService` uses latest persisted/reference market price with freshness status. If stale, valuation response exposes stale flag; execution risk still fails closed where freshness required.

## P06-W05 — Reconciliation

Create:

`paper_reconciliation_run`
- id/status/start/end/counts.

`paper_reconciliation_difference`
- run id;
- entity type/id;
- difference code;
- expected/actual JSONB;
- severity;
- resolved flag;
- resolution audit reference.

`PaperReconciliationService` verifies:
- account cash == seed + cash ledger sum;
- fill totals == order filled quantities;
- position quantities == BUY fills - SELL fills by instrument/product;
- position cost basis matches accounting rule;
- realized P&L equals sell-fill accounting;
- no rejected order has fill/cash settlement.

Never auto-delete historical inconsistencies.

## P06-W06 — Intraday end-of-session behavior

`IntradayPaperCloseService`
- scheduled only when enabled;
- after configured square-off cutoff, create explicit SYSTEM-origin SELL order intents through normal risk/accounting path;
- idempotency key derived from trade date + position;
- audit origin `INTRADAY_AUTO_CLOSE`;
- no direct position deletion.

## P06-W07 — Service decomposition

Refactor current `PaperTradingService` incrementally into:
- `PaperOrderService`
- `PaperOrderRepository`
- `PaperFillRepository`
- `PaperAccountRepository`
- `PaperPositionRepository`
- `PositionAccountingService`
- `PaperExecutionEngine`.

Do not perform big-bang rewrite. First extract repository methods under existing integration tests, then transaction orchestration.

## P06-W08 — APIs

- existing `POST /api/paper-trades/simulate` remains compatibility path;
- new `POST /api/paper-orders` eventually becomes canonical request;
- `GET /api/paper-orders/{id}`;
- `POST /api/paper-orders/{id}/cancel` for cancellable states;
- `GET /api/paper-portfolio`;
- `GET /api/paper-positions`;
- `GET /api/paper-reconciliation/runs`;
- `POST /api/paper-reconciliation` operator/admin.

## P06-W09 — UI

Existing `PaperTradesPage` evolves into tabs/sections:
- Orders
- Fills
- Positions
- Cash ledger
- Reconciliation.

New `PortfolioPage.jsx` route `/portfolio`.

Components:
- `OrderStatusTimeline`
- `FillBreakdown`
- `PositionTable`
- `PnLSummary`
- `ExposureChart`
- `CashLedgerTable`
- `ReconciliationBanner`.

### Phase 6 exit gate

Cash, orders, fills, positions, realized/unrealized P&L and snapshots reconcile under replay, restart, rejection and concurrency tests.

---

# PHASE 7 — MANUAL / AUTO / HYBRID approval policy

**Status:** NOT STARTED.

## P07-W01 — Execution policy schema

Create `execution_policy`:
- `id UUID PK`
- `policy_version INTEGER UNIQUE NOT NULL`
- `execution_mode VARCHAR(8)` (`PAPER`,`LIVE`)
- `strategy VARCHAR(80) NULL` (NULL = default)
- `trading_style VARCHAR(16)` (`INTRADAY`,`SWING`,`ANY`)
- `approval_mode VARCHAR(12)` (`MANUAL`,`AUTO`,`HYBRID`)
- `max_order_notional NUMERIC(20,2)`
- `max_daily_trades INTEGER`
- `max_gross_exposure NUMERIC(20,2)`
- `max_daily_loss NUMERIC(20,2)`
- `symbol_allowlist JSONB`
- `symbol_denylist JSONB`
- `session_start TIME NULL`
- `session_end TIME NULL`
- `active BOOLEAN`
- `effective_from/effective_to`
- actor/timestamps.

Policy limits can be stricter than hard risk limits, never looser in a way that bypasses hard risk.

## P07-W02 — Approval schema

Create `order_approval`:
- `id UUID PK`
- `order_id UUID UNIQUE FK paper_order`
- `policy_id UUID FK execution_policy`
- `policy_version INTEGER`
- `state VARCHAR(20)` (`PENDING`,`APPROVED`,`REJECTED`,`EXPIRED`,`CANCELLED`,`AUTO_APPROVED`)
- `decision_reason VARCHAR(300)`
- `requested_at`
- `expires_at`
- `decided_at`
- `decided_by VARCHAR(120) NULL`
- `row_version INTEGER NOT NULL DEFAULT 0`
- `risk_decision_id UUID NOT NULL`
- `request_snapshot JSONB NOT NULL`.

Index `(state, requested_at)`.

## P07-W03 — Policy evaluator

`ExecutionPolicyService.resolvePolicy(mode, strategy, style)` precedence:
1. exact mode+strategy+style;
2. mode+strategy+ANY;
3. mode default style;
4. mode default ANY.

`ApprovalPolicyEvaluator` returns:
- hard risk reject -> `REJECT` before policy;
- MANUAL -> `PENDING`;
- AUTO -> auto only if every policy constraint passes;
- HYBRID -> auto if stricter auto criteria pass, otherwise manual pending;
- policy failure can reject or require manual depending on explicitly classified rule; never silently promote.

## P07-W04 — Pre-execution revalidation

`ExecutionReadinessService.revalidate(orderId)` reloads:
- latest quote/reference market state;
- account cash/holdings;
- risk policy;
- emergency stop;
- approval state/expiry;
- execution policy;
- market session.

Approval is not execution authorization forever. Immediately before fill/broker submission, this service must pass again.

## P07-W05 — Concurrency

Approval update uses optimistic `row_version`.

SQL pattern:
`UPDATE order_approval SET ..., row_version=row_version+1 WHERE id=:id AND state='PENDING' AND row_version=:expected`.

Only one approver can win. Duplicate clicks return current state, not duplicate execution.

## P07-W06 — APIs

- `GET /api/approval-policies`
- `PUT /api/approval-policies/{id}`
- `GET /api/approvals?state=PENDING&page=&size=`
- `GET /api/approvals/{id}`
- `POST /api/approvals/{id}/approve`
- `POST /api/approvals/{id}/reject`
- `POST /api/approvals/{id}/cancel`.

Audit:
- `APPROVAL_REQUESTED`
- `APPROVAL_APPROVED`
- `APPROVAL_REJECTED`
- `APPROVAL_EXPIRED`
- `APPROVAL_CANCELLED`
- `AUTO_APPROVAL_GRANTED`.

## P07-W07 — UI

New route `/approvals`.

`ApprovalsPage.jsx`
- queue filters;
- pending count;
- policy mode badge;
- order/risk snapshot;
- approve/reject buttons;
- expiry countdown;
- final confirmation.

`ApprovalPolicySettings.jsx` under Settings.

Clear Invest presents guided explanation; Pro Terminal dense queue + hotkeys only for navigation, never one-key irreversible approval.

### Phase 7 exit gate

MANUAL/AUTO/HYBRID are deterministic, audited, expiry-aware, concurrency-safe and unable to override hard risk.

---

# PHASE 8 — Portfolio analytics, notifications and operations backend

**Status:** PARTIAL.

## P08-W01 — Daily performance materialization

Create `portfolio_performance_daily`:
- `trade_date DATE PK`
- opening equity;
- closing equity;
- realized P&L;
- unrealized P&L;
- fees;
- gross/net P&L;
- return %;
- max intraday drawdown %;
- trade count;
- win/loss counts;
- created/updated.

Create `strategy_performance_daily` keyed `(trade_date, strategy)` with same relevant attribution fields.

`PerformanceAggregationService` recalculates deterministically from ledger/fills; materialized rows are derived cache, not source of truth.

## P08-W02 — Analytics services

`PortfolioAnalyticsService`
- equity curve;
- drawdown series;
- period summaries;
- exposure;
- win rate;
- expectancy;
- fee attribution.

`StrategyAnalyticsService`
- strategy-level trade/P&L/research conversion metrics.

`ResearchAnalyticsService`
- scans run;
- candidate rates;
- evaluation distribution;
- backtest/scenario summaries;
- explicitly no claim of future profitability.

## P08-W03 — Notification model

Create `system_notification`:
- `id UUID PK`
- `type VARCHAR(60)`
- `severity VARCHAR(12)` (`INFO`,`WARNING`,`CRITICAL`)
- `title VARCHAR(160)`
- `message VARCHAR(500)`
- `entity_type/entity_id`
- `dedupe_key VARCHAR(160) NULL`
- `created_at`
- `acknowledged_at NULL`
- `acknowledged_by NULL`
- unique active dedupe policy implemented service-side.

`NotificationService.raise(...)` and `acknowledge(...)`.

Sources:
- provider outage;
- stale market data;
- backfill failure;
- risk emergency stop;
- reconciliation difference;
- scenario/backtest failure;
- deployment/health incident later.

## P08-W04 — Operational health aggregation

`OperationalHealthService` returns component states:
- database;
- Angel One session/read-only connectivity;
- instrument catalogue age;
- market data latest candle age;
- news ingestion age;
- scanner last run;
- reconciliation status;
- execution mode;
- emergency stop.

No secret values returned.

API `GET /api/operations/health`.

## P08-W05 — Audit search

`AuditQueryService`
- filters: event type, entity type, entity id, correlation ID, actor, from/to;
- bounded pages;
- CSV export with spreadsheet formula injection protection (prefix dangerous leading `=,+,-,@`).

## P08-W06 — APIs/UI contracts

APIs:
- `/api/analytics/portfolio`
- `/api/analytics/equity`
- `/api/analytics/strategies`
- `/api/analytics/research`
- `/api/notifications`
- `/api/notifications/{id}/acknowledge`
- `/api/operations/health`
- `/api/audit`.

UI routes later implemented fully in Phase 9:
- `/portfolio`
- `/operations`
- `/audit`.

### Phase 8 exit gate

Operator can understand account performance, strategy/research performance, component health, notifications and audit trail entirely through supported APIs.

---

# PHASE 9 — Complete dual frontend experiences

**Status:** IN PROGRESS. Existing `AppContext` experience selector and first Clear Invest dashboard remain.

## P09-W01 — Frontend architecture refactor

Keep `App.jsx` routing as single source. Add routes:

```text
/research
/research/candidates/:candidateId
/portfolio
/approvals
/operations
/notifications
/settings
/audit
```

Introduce:

`components/experience/ExperienceBoundary.jsx`

```jsx
<ExperienceBoundary
  pro={<ProTerminalResearchView ... />}
  clear={<ClearInvestResearchView ... />}
/>
```

Page components own data/controller state; variant views receive view models/callbacks. Variant views must not call APIs directly.

## P09-W02 — Design tokens

Create:
- `styles/tokens/base.css`
- `styles/tokens/pro-terminal.css`
- `styles/tokens/clear-invest.css`
- `lib/experience.js` typed constants/validation.

Token groups:
- semantic background/surface/text/border;
- positive/negative/warning/info;
- risk severity;
- spacing scale;
- radii;
- elevations;
- typography scale;
- chart grid/axis/series semantic tokens;
- focus ring;
- motion duration/easing.

No component hard-codes experience-specific colors when a semantic token exists.

## P09-W03 — Shared page-controller pattern

For every major page create:

```text
<Page>.jsx                   // route + data/controller
components/<domain>/shared/* // shared semantic building blocks
components/<domain>/pro/*    // Pro composition
components/<domain>/clear/*  // Clear composition
```

Example research:

```text
ResearchPage.jsx
components/research/shared/CandidateReasonList.jsx
components/research/pro/ProResearchView.jsx
components/research/clear/ClearResearchView.jsx
```

## P09-W04 — Required screen inventory

Both variants must implement the same actions/states for:

1. Overview
2. Instruments
3. Market Data
4. Research scanner
5. Candidate detail
6. Backtesting/walk-forward
7. Scenario/regime analysis
8. Signals
9. Simulated order review
10. Approval queue
11. Orders/fills
12. Positions/holdings
13. Portfolio analytics
14. Risk policy/decisions
15. News context
16. Notifications
17. Operations health
18. Settings/experience/runtime mode
19. Audit history.

## P09-W05 — Data state requirements

Every screen explicitly renders:
- initial loading skeleton;
- empty state;
- recoverable error + retry;
- unauthorized state;
- stale-data badge where backend exposes age;
- mutation pending state preventing duplicate click;
- success state;
- immutable reason/audit details when a decision rejects.

No spinner-only indefinite state.

## P09-W06 — Experience switching

Current local preference remains fallback. After Phase 10 auth:
- anonymous/local -> localStorage;
- authenticated -> load server `user_preference`;
- on login, server preference wins unless absent, then persist local preference to server;
- switch updates server asynchronously and local immediately;
- route/form/query cache does not remount/clear;
- API key/session/auth never depends on selected design.

## P09-W07 — Responsive matrix

Mandatory viewport checks:
- 320
- 375
- 411
- 768
- 1024
- 1440 px.

Minimum interactive target 44 CSS px on mobile where practical. Dense desktop controls may be smaller but remain keyboard accessible.

Safe-area CSS for bottom navigation/header.

## P09-W08 — Accessibility

- visible focus;
- semantic headings;
- labels/error associations;
- no color-only P&L/risk meaning;
- charts have text summary/table fallback;
- dialogs trap/restore focus;
- `prefers-reduced-motion` respected;
- contrast automated check in CI where possible.

## P09-W09 — Frontend tests

Add Vitest/RTL if not already adequate and Playwright for E2E.

Required E2E flows in BOTH experiences:
- sign in/unlock operator session;
- start research scan -> inspect candidate -> run evaluation;
- position-size -> submit PAPER order -> risk result -> approval when policy requires -> fill/order history;
- emergency stop blocks order;
- switch experience mid-form without losing inputs;
- backtest run/history;
- degraded market-data error state.

Visual snapshots for key screens at mandatory widths.

### Phase 9 exit gate

Every supported feature exists in both experiences with equivalent behavior, responsive/accessibility quality and E2E evidence.

---

# PHASE 10 — Authentication, authorization and security hardening

**Status:** PARTIAL. Existing API-key filter is development/operator baseline only.

## P10-W01 — Production user/role model

Create:

`app_user`
- `id UUID PK`
- `email VARCHAR(254) UNIQUE NOT NULL`
- `display_name VARCHAR(120)`
- `password_hash VARCHAR(255)` if local credentials are retained;
- `status VARCHAR(16)` (`ACTIVE`,`LOCKED`,`DISABLED`)
- `created_at/updated_at/last_login_at`.

`app_role`
- codes `ADMIN`, `TRADER`, `VIEWER`.

`app_user_role`
- composite PK user/role.

`user_preference`
- `user_id UUID PK/FK`
- `experience VARCHAR(20)`
- `theme VARCHAR(20)`
- `timezone VARCHAR(60)` default `Asia/Kolkata` display preference;
- updated_at.

If an external IdP is selected before implementation, replace password columns with provider subject mapping, but preserve internal user/role IDs.

## P10-W02 — Session model

Prefer HttpOnly Secure SameSite cookie session for first-party web rather than browser-stored bearer token.

If persistent DB sessions are used:

`auth_session`
- id UUID PK;
- user id;
- token hash only, never raw token;
- created/expires/revoked/last_seen;
- user agent hash/IP metadata only if justified and privacy-reviewed.

CSRF protection required for cookie-authenticated mutations.

Existing API-key auth:
- allowed only in local/dev or explicit automation endpoint scope;
- production startup must fail if insecure default key configured;
- never store API key in localStorage (current behavior already memory-only).

## P10-W03 — Authorization matrix

- VIEWER: read research/analytics/orders/audit allowed as configured, no mutations.
- TRADER: PAPER order/approval actions, research runs; cannot change security/risk/admin policy unless explicitly allowed.
- ADMIN: risk policy, execution policy, user/role, emergency controls, deployment-facing settings.
- LIVE activation later requires separate role/privilege and explicit Phase 15 gate.

Annotate service/controller authorization; do not rely on hiding buttons.

## P10-W04 — Security hardening

- strict CORS allowlist;
- CSRF for cookie auth;
- request size limits;
- rate limit login/sensitive mutations;
- security headers CSP/HSTS/X-Content-Type-Options/Referrer-Policy;
- no secrets in logs;
- credential values from environment/Secrets Manager only;
- sanitize provider error logging;
- dependency scanning;
- container non-root user;
- read-only filesystem where feasible;
- database least-privilege app user.

## P10-W05 — Security tests

- unauthenticated matrix;
- role matrix;
- CSRF rejection;
- CORS rejection;
- login brute-force/rate-limit behavior;
- API-key disabled production profile;
- IDOR tests for every resource-by-ID endpoint;
- secrets/log regression scans.

### Phase 10 exit gate

Production does not rely on a shared browser API key; all mutations are authenticated/authorized, security boundaries are tested, and secrets are server-only.

---

# PHASE 11 — Mobile web/PWA and platform validation

**Status:** PARTIAL.

## P11-W01 — PWA cache policy

Service worker may cache only:
- versioned static assets;
- shell HTML with safe network strategy;
- optional read-only GET responses only if explicitly reviewed.

Never cache/replay mutation requests under:
- `/paper-*`
- `/approvals`
- `/risk/emergency-stop`
- `/system/execution-mode`
- future `/broker`/LIVE routes.

Offline UI must show `Offline — trading actions unavailable` and disable mutations.

## P11-W02 — Installability assets

Required production assets:
- 192x192 PNG;
- 512x512 PNG;
- maskable 512x512;
- apple-touch-icon;
- favicon variants;
- manifest start_url/scope/display standalone;
- theme/background colors matching brand.

CI validates existence/dimensions/manifest links.

## P11-W03 — Android/iOS position

Required V1 platform support:
- desktop web;
- mobile web;
- installable PWA on Android/iOS where browser supports it.

Android TWA may be shipped after domain/TLS is active:
- Digital Asset Links at `/.well-known/assetlinks.json`;
- verified package/signing fingerprint;
- fullscreen trusted origin behavior;
- Play Store screenshots/policy checklist.

Do not claim native iOS app unless a native wrapper actually exists. iOS Safari PWA behavior is mandatory; a native iOS shell is optional future scope unless explicitly promoted.

## P11-W04 — Device validation

At minimum:
- Android Chrome physical device;
- Android standalone PWA/TWA if used;
- iPhone Safari/PWA;
- desktop Chrome/Edge/Safari where available.

Check:
- viewport/safe areas;
- keyboard/form behavior;
- reconnect handling;
- no browser toolbar in verified TWA;
- external links leave app safely;
- no duplicate order on network retry.

### Phase 11 exit gate

Supported web/PWA platforms pass functional/mobile/security tests with trading mutations disabled offline.

---

# PHASE 12 — AWS production infrastructure

**Status:** EXTERNAL WAIT for account activation. Not a blocker for other phases.

## P12-W01 — Infrastructure profile decision

Before apply, create/update ADR selecting exactly one initial stack:

- `infra/terraform-budget` for cost-minimized single-instance PAPER launch, or
- `infra/terraform` for managed ECS/RDS architecture.

Never apply both to the same environment.

Selection criteria:
- expected users/load;
- availability requirement;
- monthly budget;
- operational tolerance;
- LIVE timeline.

## P12-W02 — Environment separation

At minimum Terraform/workspace/state separation:
- `staging`
- `production`.

Remote Terraform state with encryption and locking after AWS account exists. No production secrets/state committed to Git.

## P12-W03 — Budget stack target details

If selected:
- region `ap-south-1` unless ADR changes based on current pricing/availability;
- ARM64 EC2;
- Docker Compose backend/frontend/Postgres;
- encrypted EBS;
- S3 encrypted backups;
- IAM instance role;
- SSM access, no public SSH requirement;
- HTTPS reverse proxy/ALB strategy documented;
- CloudWatch logs/metrics;
- AWS Budget alerts.

Restore drill is mandatory because database is single-instance.

## P12-W04 — Managed stack target details

If selected:
- VPC multi-AZ subnets;
- public ALB, private app/RDS;
- ECS Fargate;
- ECR immutable commit tags;
- RDS PostgreSQL encrypted + backups + deletion protection;
- Secrets Manager;
- CloudWatch logs/alarms;
- ACM + Route53;
- GitHub OIDC deployment role scoped to repo/branch/environment.

## P12-W05 — Deployment flow

Manual/approved production workflow:
1. CI green;
2. build immutable images tagged commit SHA;
3. vulnerability scan;
4. Terraform plan artifact reviewed;
5. apply infra when needed;
6. run Flyway migration as controlled release step;
7. deploy services;
8. health/readiness smoke tests;
9. rollback service image on failure; database rollback uses forward fix, not destructive Flyway downgrade.

No production workflow changes `TRADING_MODE` to LIVE.

## P12-W06 — Secrets

Secrets:
- DB credentials;
- operator auth secret/session key;
- Angel One credentials;
- optional Gemini key;
- news API token if still used;
- encryption/signing secrets.

Runtime receives them via Secrets Manager/SSM/environment injection; never Terraform output plaintext or frontend bundle.

### Phase 12 exit gate

Staging and production infrastructure are reproducibly provisioned, TLS/secrets/backups/alerts verified, and PAPER app deploys from immutable CI artifacts.

---

# PHASE 13 — Observability, reliability and production hardening

**Status:** PARTIAL.

## P13-W01 — Metrics

Use Spring Actuator/Micrometer. Custom metric names:

- `aitrading_marketdata_provider_requests_total`
- `aitrading_marketdata_gap_count`
- `aitrading_scanner_runs_total`
- `aitrading_backtest_runs_total`
- `aitrading_scenario_runs_total`
- `aitrading_risk_decisions_total{outcome,reason}`
- `aitrading_paper_orders_total{status}`
- `aitrading_approvals_total{state}`
- `aitrading_reconciliation_differences_total{severity}`
- `aitrading_broker_requests_total` only after Phase 15.

Avoid symbol/user IDs as high-cardinality metric labels.

## P13-W02 — Structured logging

JSON logs in production with:
- timestamp;
- level;
- service/version;
- correlation ID;
- event name;
- entity ID when safe;
- no token/password/full provider payload.

## P13-W03 — Health/readiness

Liveness: process/JVM only.

Readiness: DB + critical migrations/config. External broker/news should not necessarily make process unready; expose degraded component status separately to avoid restart loops.

## P13-W04 — Alerts

Critical:
- DB unavailable;
- repeated app crash/restart;
- emergency stop active unexpectedly/long duration;
- reconciliation critical difference;
- LIVE broker unknown state later.

Warning:
- stale market data;
- provider failures;
- backfill failures;
- elevated 5xx;
- disk/EBS threshold budget stack.

## P13-W05 — Reliability testing

- restart during PAPER order workflow;
- concurrent duplicate order submissions;
- database transient failure;
- Angel One read timeout/throttle;
- stale quote/candle;
- malformed provider payload;
- scanner partial instrument failure;
- simulation insufficient block pool;
- reconciliation mismatch detection;
- container restart and DB recovery.

## P13-W06 — Performance budgets

Establish measured targets before release, including:
- dashboard API p95;
- research scan max-universe duration;
- 10k scenario simulation duration;
- order risk/evaluation transaction duration;
- frontend initial JS bundle size;
- key page LCP/interaction baseline.

Targets must be based on staging measurements, not invented numbers.

### Phase 13 exit gate

Known failure modes are observable, alerted, recoverable and exercised through repeatable tests/runbooks.

---

# PHASE 14 — Production PAPER/research V1 launch

**Status:** NOT STARTED until Phases 1–13 mandatory gates complete.

## P14-W01 — Release candidate freeze

Create release branch/tag only after:
- required phase checklist green;
- zero unresolved critical security/reconciliation defects;
- production migrations validated from clean DB and upgraded staging DB;
- dual UI E2E matrix green.

## P14-W02 — Production seed/config

Required controlled setup:
- production admin/operator user;
- active risk policy;
- PAPER execution policy;
- starting PAPER capital;
- instrument catalogue sync;
- bounded historical data bootstrap;
- holiday/calendar data;
- emergency stop default known;
- LIVE hard-disabled.

## P14-W03 — Smoke test sequence

1. login/auth;
2. health/operations page;
3. instrument search;
4. market-data recent candle/backfill;
5. research scan;
6. candidate evaluation/backtest/scenario;
7. position-size advisory;
8. PAPER order happy path;
9. hard risk reject path;
10. emergency stop reject path;
11. approval path according to PAPER policy;
12. order/fill/cash/position reconciliation;
13. both Pro Terminal and Clear Invest;
14. mobile PWA smoke.

## P14-W04 — Operational signoff

Required artifacts:
- release commit/tag;
- CI URL;
- migration evidence;
- backup evidence;
- restore drill result;
- security checklist;
- known limitations;
- rollback procedure;
- support/runbook contacts/process.

## P14-W05 — V1 completion definition

Product is considered **production live for PAPER/research** when:
- public/approved production endpoint is healthy;
- authentication enforced;
- market/research/PAPER flows function;
- both designs work;
- monitoring/backups exist;
- no LIVE broker submission code path can be activated through normal UI/config.

---

# PHASE 15 — Optional Angel One real-money LIVE pilot

**Status:** SEPARATELY GATED. Phase 14 success does not automatically authorize Phase 15 activation.

## P15-W01 — Broker abstraction

Create:

```java
public interface BrokerOrderGateway {
    BrokerOrderReceipt submit(BrokerOrderRequest request);
    BrokerOrderStatus getOrder(String brokerOrderId);
    BrokerCancelReceipt cancel(String brokerOrderId);
    BrokerAccountSnapshot account();
}
```

Implementations:
- `broker/mock/MockBrokerOrderGateway`
- `broker/angelone/AngelOneBrokerOrderGateway`.

All contract tests first run against mock. Angel One submission method remains runtime-disabled until activation gate.

## P15-W02 — LIVE intent schema

Create `live_order_intent`:
- `id UUID PK`
- `client_order_id VARCHAR(80) UNIQUE`
- `request_hash CHAR(64)`
- instrument/exchange/symbol;
- side/quantity/order type/limit/product;
- strategy/candidate;
- risk decision ID;
- approval ID;
- state;
- correlation ID;
- created/updated.

State machine:

`CREATED -> RISK_APPROVED -> APPROVAL_PENDING -> READY_TO_SUBMIT -> SUBMITTING -> ACKNOWLEDGED -> PARTIALLY_FILLED -> FILLED`

Terminal/exception:
- `REJECTED`
- `CANCELLED`
- `EXPIRED`
- `UNKNOWN`.

`UNKNOWN` is mandatory for network timeout/ambiguous submission. Never retry submission blindly from UNKNOWN.

## P15-W03 — Broker order/fill schema

`broker_order`
- intent ID unique;
- broker name;
- broker order ID unique nullable until ack;
- raw status normalized + bounded sanitized provider code;
- submitted/ack/updated timestamps.

`broker_fill`
- broker order ID;
- broker fill/trade ID unique;
- quantity/price/fees if available;
- fill timestamp;
- raw provider status code bounded.

## P15-W04 — LIVE submission workflow

1. authenticated operator creates immutable live intent;
2. deterministic risk evaluation;
3. approval policy;
4. immediately before submit call `ExecutionReadinessService.revalidate`;
5. transaction sets `READY_TO_SUBMIT -> SUBMITTING` and commits;
6. external broker call;
7. persist ACK/REJECT/UNKNOWN;
8. reconciliation polls/status endpoint/webhook as provider supports;
9. fills update broker records and live portfolio accounting;
10. every transition audited.

## P15-W05 — Read-only Angel One integration first

Before order submission implementation is enabled, verify:
- auth/session lifecycle;
- funds;
- holdings;
- positions;
- order book;
- provider clock/session behavior;
- rate limits/error codes against current official Angel One docs.

## P15-W06 — Compliance/regulatory gate

Before real-money submission:
- review current SEBI/exchange/broker retail API/algo requirements from official current sources;
- verify broker terms, supported order types, session rules, rate limits and account permissions;
- document required operator declarations/approvals;
- no assumption based on old docs.

## P15-W07 — Activation controls

Require all simultaneously:
- `live.feature.enabled=true` server-side deployment setting;
- production execution mode change authorized by ADMIN/LIVE_ACTIVATOR role;
- active LIVE execution policy;
- broker credentials/account verified;
- emergency stop tested;
- monitoring/alerts active;
- explicit pilot limit config;
- explicit go-live approval recorded.

No single environment variable alone may enable real-money orders.

## P15-W08 — Pilot

Pilot begins MANUAL only:
- tiny configured notional cap;
- small daily order cap;
- allowlisted symbols;
- market-hours only;
- reconcile every order/fill;
- stop immediately on UNKNOWN/reconciliation divergence/provider instability.

AUTO/HYBRID for LIVE is not enabled until separate post-pilot approval.

### Phase 15 exit gate

A separately approved low-limit real-money pilot completes with correct broker reconciliation, no duplicate submissions, tested emergency stop and documented compliance/operational signoff.

---

# 4. Mandatory implementation sequencing and PR granularity

The current active sequence is:

```text
P04 scenario/regime/provenance
  -> P05 risk completion
  -> P06 PAPER accounting/portfolio
  -> P07 approval workflow
  -> P08 analytics/operations
  -> P09 complete dual UI
  -> P10 production auth/security
  -> P11 mobile/PWA validation
  -> P12 AWS when account is available
  -> P13 hardening/reliability
  -> P14 PAPER/research production launch
  -> P15 separately gated LIVE pilot
```

Phases 1–3 remaining hardening can run before or alongside P04–P08 when dependencies are clear, but their exit gates must be complete before Phase 14.

## PR sizing rule

A PR should normally implement one work item (`Pxx-Wyy`) or a coherent subset. A PR must not combine unrelated database, UI and infrastructure work merely because they belong to the same phase.

For a schema-bearing work item, expected PR order is usually:

1. forward Flyway migration + migration integration test;
2. repository/domain model;
3. service/business rules + unit tests;
4. controller/API + integration tests;
5. frontend hook/controller;
6. both experience compositions + UI/E2E tests;
7. docs/status update.

Security/risk/accounting changes require regression tests before merge.

---

# 5. Definition of implementation completion for every work item

A work item is not complete because classes exist. It is complete only when applicable evidence exists for all of:

- migration applied from prior production-like schema;
- repository query/index correctness;
- unit tests for domain boundaries;
- PostgreSQL integration tests for persistence/transactions;
- controller/API validation/auth tests;
- frontend loading/empty/error/success behavior;
- Pro Terminal + Clear Invest parity when UI-visible;
- mobile responsive behavior when UI-visible;
- audit event where mutation is security/trading significant;
- no secret logging;
- CI green;
- master plan/status updated.

---

# 6. Global V1 non-goals unless explicitly promoted

The following do not silently become mandatory V1 scope:

- F&O/options/futures;
- short selling beyond explicitly supported product rules;
- scalping/high-frequency trading;
- autonomous AI order authorization;
- social/copy trading;
- multi-broker execution;
- native iOS application if only PWA is shipped;
- multi-tenant brokerage SaaS;
- strategy marketplace;
- ML price prediction.

Adding any of these requires an explicit product-scope change to `MASTER_IMPLEMENTATION_PLAN.md`, not an endless new implementation phase.

---

# 7. Source-of-truth hierarchy

1. `docs/MASTER_IMPLEMENTATION_PLAN.md` — product scope, finite phases, launch gates.
2. `docs/MASTER_LOW_LEVEL_IMPLEMENTATION_DESIGN.md` — exact technical implementation and work-item IDs.
3. Phase/design/provider documents — supporting rationale/reference.
4. GitHub issues — execution tracking.
5. PR descriptions — change-specific evidence.

If lower-level material conflicts with items 1 or 2, update the source-of-truth documents explicitly before treating the conflicting behavior as intended architecture.


---

# ALL-PHASE SECOND-PASS DESIGN REVIEW — MANDATORY REFINEMENTS (2026-10-28)

This section reviews **every phase 0–15**, not only Phase 5. It supplements the phase work items above and is binding on implementation PRs. A separate detailed Phase 5 design exists at \`docs/PHASE_5_RISK_ENGINE_LOW_LEVEL_DESIGN.md\`; its stricter Phase 5 contracts prevail. The review is a **design refinement, not evidence that code or tests are complete**.

## Cross-phase decisions to settle before coding

1. **Canonical identifiers and ownership:** document exact existing SQL PK/FK types before migrations; no guessed UUID/BIGINT links. Every account-scoped table/API/query must carry paper_account_id (and user/tenant ownership once identity exists). Instrument identity must reference catalogue IDs, not ambiguous symbols.
2. **Canonical lifecycle and failure semantics:** define legal status transitions, transaction owner, retry/idempotency fingerprint, error response and audit event for every mutating operation. No order/approval/broker submission in a GET or background replay.
3. **Time and money:** UTC Instant/TIMESTAMPTZ for persistence, Asia/Kolkata for trading sessions; use exchange holidays and special sessions. BigDecimal with explicit currency, rounding, tick-size and quantity rules; fees/taxes/slippage are versioned assumptions.
4. **Evidence lineage:** candidate -> scan -> strategy/config -> candle cutoff/quality -> backtest -> scenario -> risk -> order/approval -> fill -> ledger/portfolio -> audit must have immutable references. Every derived value records methodology version and as-of time.
5. **Deployment safety:** all new database changes expand/backfill/verify/cutover/contract; test upgrade from the existing schema. No production LIVE orders until a separately approved Phase 15 release gate.
6. **UI parity:** each feature PR identifies Pro Terminal and Clear Invest screens, loading/empty/error/stale states, keyboard/mobile accessibility, safe action confirmation, query invalidation and end-to-end parity evidence.
7. **CI evidence:** attach migration integration tests, deterministic replay, negative/failure tests, API contract tests and at least one affected UI path. Do not mark a phase DONE based solely on a document or a merged PR.

## Phase-by-phase review additions

### Phase 0 — governance and CI
- P00-R01: add an automated phase-work-item manifest (ID, owner, dependencies, acceptance test, status, PR, CI run), and reject undocumented phase completion.
- P00-R02: pin Java/Node/PostgreSQL/Testcontainers and frontend package lock; add secret scanning, dependency scanning and migration smoke test against upgrade-from-current schema.
- P00-R03: document architecture decision records for PAPER/LIVE boundary, dual UI, idempotency, transaction isolation and market data source of truth.

### Phase 1 — instrument and market data
- P01-R01: enforce exchange+segment+instrument token+catalogue version identity and handle delisted/renamed/corporate-action instruments without rewriting historical signals.
- P01-R02: record source timezone, candle completeness, late correction/version, corporate action adjustment policy, volume validity and market holiday/special-session calendar; define data-quality eligibility thresholds by strategy/interval.
- P01-R03: implement bounded provider throttling, backfill resume/checkpoint, deduplicated ingestion and reconciliation; include provider outage, duplicate and out-of-order fixtures.
- P01-R04: require UI to show data age, coverage %, gap count, source and last reconciliation, not just a green/red health badge.

### Phase 2 — strategies and scanning
- P02-R01: pin strategy code+version+typed config schema, candle cutoff, universe and candidate fingerprint; reject non-deterministic or unbounded plugin code.
- P02-R02: define universe inclusion/exclusion, circuit-limit/illiquidity handling, long-only cash-equity rules, duplicate signals and stable score tie-breaks.
- P02-R03: enforce bounded scans, partial-failure diagnostics, cancellation/timeout and replay with the same input snapshot; fixture-test each strategy at exact indicator thresholds.
- P02-R04: present scanner reasons, risk flags, data freshness and research-only status identically in both UI experiences.

### Phase 3 — backtesting
- P03-R01: separate train/validation/test periods; use walk-forward and no-lookahead guards, next-bar execution rules and survivorship/corporate-action limitations.
- P03-R02: model fees, taxes, slippage, partial fills/volume assumptions and intraday session gaps with versioned methodology; explicitly mark any unavailable historical adjustment data.
- P03-R03: persist benchmark definition, trade-by-trade evidence, equity/drawdown points, run fingerprint and failure reason; verify deterministic replay and no future-candle access.
- P03-R04: include sample size, confidence/uncertainty and overfitting warnings in both UI experiences; do not equate historical backtest performance with future results.

### Phase 4 — scenario research
- P04-R01: ensure block bootstrap never crosses trade date/session, never samples after cutoff, and records fallback tier when regime-matched history is insufficient.
- P04-R02: make regime classification past-only, record window/threshold methodology and enforce minimum independent sessions/eligible blocks; reject insufficient history instead of manufacturing confidence.
- P04-R03: store seed, input fingerprint, costs, sample counts, probability counts, stop-first tie rule, percentiles and immutable candidate/backtest linkage.
- P04-R04: show uncertainty, methodology and 'research only' distinction in both experiences; simulations must never directly authorize an order.

### Phase 5 — deterministic risk
- P05-R01: implement account-scoped consistent snapshot and pending-order cash/exposure reservations; race-test simultaneous orders, emergency stop and policy changes.
- P05-R02: define entry versus protective exit rules, market freshness, tick/lot validation, daily P&L basis and hard-reject precedence.
- P05-R03: persist policy snapshot, context hash and every rule result; migrate V1 decisions without changing their meaning; add deterministic replay.
- P05-R04: position sizing is advisory; actual execution recomputes after account lock. Use the dedicated Phase 5 replacement design for full acceptance matrix.

### Phase 6 — PAPER accounting
- P06-R01: choose and document cost-basis convention, fee/tax allocation, rounding and ledger double-entry invariants; every fill must reconcile to order, cash and position.
- P06-R02: support partial fills and cancellation races with immutable fill sequence, unique idempotency and no negative cash/long-only quantity; reconcile restart/retry.
- P06-R03: distinguish reserved, available and settled cash; mark stale valuations and never treat stale prices as zero exposure.
- P06-R04: define intraday square-off calendar, missing-price fallback, failure escalation and audit; never delete a position as a shortcut.
- P06-R05: include position, fill, cash-ledger and reconciliation drilldowns in both UI experiences.

### Phase 7 — MANUAL/AUTO/HYBRID approvals
- P07-R01: policy resolution precedence, versioned policy snapshot and hard-risk-first behavior must be deterministic and unit-tested.
- P07-R02: use compare-and-set approval row version, expiry and one-time execution intent; revalidate fresh risk, cash and market data after approval.
- P07-R03: distinguish approval permission from order execution permission; audit actor, reason, time, before/after, and deny self-approval if required by governance.
- P07-R04: define queue ownership, stale/expired/duplicate-click UX and safe notifications in both designs; no offline approval replay.

### Phase 8 — portfolio, analytics, alerts and operations
- P08-R01: define metric formulas and denominators (realized/unrealized P&L, returns, drawdown, win rate, fees, exposure) and reconciliation to Phase 6 ledger.
- P08-R02: define event outbox or durable notification claim/retry, dedupe keys, retention, acknowledgment and delivery failures; alerts must not be silently lost.
- P08-R03: separate stale market data from application outage and broker outage in operational health; define escalation thresholds and runbooks.
- P08-R04: ensure exports are account-scoped, paginated, timezone-explicit and CSV-injection safe.

### Phase 9 — dual design system and UI
- P09-R01: inventory every route, shared controller, Pro composition, Clear composition, permissions, keyboard flow and mobile breakpoints; enforce parity with a route-feature matrix.
- P09-R02: codify semantic tokens for positive/negative/neutral/warning/stale, density, typography, spacing, charts and motion; never rely on color alone for risk.
- P09-R03: preserve route, form draft, focus and in-flight mutations when switching experiences; never duplicate API mutation or reset execution mode.
- P09-R04: add visual regression and accessibility checks for 320/375/411/768/1024/1440, reduced motion, empty/loading/error/offline/stale, long numbers and INR formatting.
- P09-R05: define design-to-code asset governance and component ownership; no neumorphism, and no screen may claim LIVE enabled while backend blocks it.

### Phase 10 — authentication and authorization
- P10-R01: select identity provider via ADR; define actor, account ownership, roles and per-endpoint permission matrix before switching operator-key auth.
- P10-R02: specify session expiry/refresh/revocation, CSRF/CORS, secret handling, audit identity, IDOR protection and safe auth migration.
- P10-R03: role-restrict policy changes, resume from emergency stop, approvals, exports and operational actions at backend, not only UI.
- P10-R04: test cross-account access and privilege escalation for all existing and new endpoints.

### Phase 11 — PWA and mobile
- P11-R01: offline shell is read-only with conspicuous stale state; prohibit background-sync/order/approval/policy/emergency-stop mutation replay.
- P11-R02: test installability, manifest, asset links/TWA full-screen verification, viewport and back navigation on real Android devices.
- P11-R03: make iOS distribution a documented decision (PWA vs native wrapper/App Store) rather than promising App Store availability from PWA metadata.
- P11-R04: test device reconnection, interrupted confirmation, app background/resume and notification permissions without duplicate mutations.

### Phase 12 — AWS
- P12-R01: choose one deployment topology by ADR with monthly cost envelope, scaling ceiling, failure domain and recovery objectives; do not maintain two production stacks.
- P12-R02: implement remote state locking, environment isolation, least privilege/OIDC, secrets rotation, encrypted DB backups and tested restore.
- P12-R03: define deploy/rollback, health probes, schema compatibility and smoke tests before first apply; account activation is a provisioning dependency only.
- P12-R04: no provider credentials in repo, Terraform state output, frontend bundle or CI logs.

### Phase 13 — reliability
- P13-R01: define measurable SLOs for API, ingestion, scan, simulation and order/risk paths based on load tests; bound metric label cardinality.
- P13-R02: test provider 429/outage, DB failover, interrupted transaction, duplicate event, restart during run, stale data and reconciliation recovery.
- P13-R03: document incident ownership, kill-switch drills, backup restore drills, retention/privacy and operational audit review.

### Phase 14 — production PAPER launch
- P14-R01: require an evidence-backed release checklist mapping every Phase 0–13 exit criterion to CI, runbook, migration, accessibility and security proof.
- P14-R02: perform staged production PAPER smoke test with no broker submission, data-quality checks, simulated risk rejection, restart/reconcile and rollback drill.
- P14-R03: define explicit go/no-go signoff, known limitations, monitoring window, incident response and launch rollback triggers.

### Phase 15 — separately gated LIVE pilot
- P15-R01: verify current Indian regulatory/broker permissions, Angel One official order API behavior and exchange product rules before any LIVE enablement.
- P15-R02: isolate broker credentials and LIVE account from PAPER; durable order intent and reconcile-before-retry for unknown submission outcomes.
- P15-R03: require separate server-side feature flag, operator permission, manual pilot limits, live emergency stop and broker/account reconciliation.
- P15-R04: test duplicate callbacks, broker timeout, partial fill, rejection, exchange outage and cancel race in mock/sandbox before real funds.
- P15-R05: LIVE remains disabled until explicit separate approval and documented launch gate; AI never has order-submission authority.

## Required PR review template (all phases)

Every PR must include: phase/work-item IDs; current-code paths inspected; exact SQL migration and rollback/forward-fix plan; Java interfaces/classes and API contract; UI parity checklist; test classes and CI links; security and PAPER/LIVE impact; migration compatibility; remaining gaps. Update the status table only with verified evidence.
