# AI Trading System — Master Low-Level Implementation Design

> **Status:** AUTHORITATIVE TECHNICAL EXECUTION COMPANION  
> **Scope authority:** `docs/MASTER_IMPLEMENTATION_PLAN.md`  
> **Repository:** `operationReact/AiTradingSystem`  
> **Integration branch:** `dev`  
> **Last reconciled:** 2026-10-28  
> **Purpose:** Define the concrete database, backend, API, frontend, test, infrastructure and launch implementation for every phase through production PAPER launch and the separately gated LIVE pilot.

---

## 1. Authority and engineering rules

`docs/MASTER_IMPLEMENTATION_PLAN.md` remains the product/phase source of truth. This document is the low-level implementation source of truth. When a phase is implemented, engineers should follow the classes, tables, APIs, state transitions and UI ownership defined here unless a PR explicitly updates both documents.

Rules:

1. Extend existing packages/classes before creating parallel implementations.
2. Java 21 + Spring Boot + JdbcTemplate/PostgreSQL/Flyway remain the backend baseline.
3. Flyway migrations are forward-only. Use the next available version at implementation time (`V<next>__...sql`) rather than reserving numbers months in advance.
4. All money/price/risk values use `BigDecimal`; never `double` for persisted trading/accounting calculations.
5. Persist timestamps as `TIMESTAMPTZ` and use `Instant` in Java; use `Asia/Kolkata` only for NSE/BSE session/calendar interpretation.
6. Every order/research/risk workflow has a correlation/idempotency identity and immutable provenance.
7. AI/news are research-only inputs and never authorize or submit trades.
8. `RiskPolicy` remains a hard execution gate. Approval policy cannot override a hard rejection.
9. PAPER remains default. LIVE submission remains disabled until Phase 15 activation gates pass.
10. Pro Terminal and Clear Invest share the same backend APIs, React Query cache and business rules. They differ only in composition/presentation.
11. AWS account activation is not a blocker for Phases 1–11 and 13. Terraform review and application code continue while AWS is unavailable.

---

## 2. Target package and frontend structure

Existing backend roots remain authoritative:

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
├── provider
│   └── angelone
├── research
│   └── simulation
├── risk
├── scanner
├── security
└── strategy
```

New code should be added under these roots unless the domain is genuinely new. Planned additions:

```text
in.aitradingsystem
├── approval
├── analytics
├── notification
├── portfolio
├── reconciliation
└── broker
    ├── BrokerOrderGateway.java
    ├── mock
    └── angelone
```

Frontend continues under:

```text
frontend/src
├── components
├── context
├── hooks/useApi.js
├── lib
├── pages
└── styles.css
```

As scope grows, split `useApi.js` without changing hook behavior:

```text
frontend/src/hooks/api/
├── useMarketDataApi.js
├── useResearchApi.js
├── useBacktestApi.js
├── useRiskApi.js
├── usePaperApi.js
├── usePortfolioApi.js
├── useApprovalApi.js
├── useNotificationApi.js
└── useOperationsApi.js
```

Shared API client/auth header handling stays centralized in `frontend/src/lib/apiClient.js` (extract from current behavior when the split is implemented).

---

# Phase 0 — Architecture, safety and CI foundation

**Status:** DONE / governance only.

## Backend

Keep existing:

- `AiTradingSystemApplication`
- `TradingModeGuard`
- `ExecutionModeController`
- `AuditService`
- Spring configuration classes in `config`
- Java 21 Maven build

Add only governance utilities when required:

- `CorrelationIdFilter` — generate/propagate `X-Correlation-Id`.
- `ApiExceptionHandler` — stable error contract `{code,message,correlationId,timestamp}` without raw stack traces.
- `Clock` bean in `AppConfig` and inject it anywhere time affects deterministic logic.

## Database

Do not replace current schema. Future migrations must be additive. Add indexes only from measured query needs. Every new mutable business table should normally include:

```sql
created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at TIMESTAMPTZ,
version BIGINT NOT NULL DEFAULT 0
```

`version` is required where optimistic state transitions are needed.

## CI

Keep `.github/workflows/phase0-validation.yml` as mandatory baseline:

- Maven tests with PostgreSQL/Testcontainers;
- frontend production build;
- PWA/mobile metadata validation;
- backend/frontend Docker builds;
- full Terraform validation;
- budget Terraform validation.

Later phases add jobs but must not weaken these.

## Frontend

Keep `App.jsx`, `AppShell.jsx`, `ModeControl.jsx`, `DataState.jsx`, shared `ui.jsx` and existing route/state behavior.

## Exit tests

- `TradingModeGuard` rejects accidental LIVE startup/config drift.
- no API returns secrets;
- CI required checks green on `dev`.

---

# Phase 1 — Instrument master and market-data production readiness

**Status:** PARTIAL. Extend existing `instrument`, `marketdata`, `provider.angelone` implementations.

## Database

### New table: `market_data_quality`

```sql
id BIGSERIAL PRIMARY KEY,
instrument_id BIGINT NOT NULL,
interval VARCHAR(32) NOT NULL,
window_start TIMESTAMPTZ NOT NULL,
window_end TIMESTAMPTZ NOT NULL,
expected_candles INT NOT NULL,
actual_candles INT NOT NULL,
missing_candles INT NOT NULL,
duplicate_candles INT NOT NULL DEFAULT 0,
status VARCHAR(20) NOT NULL, -- HEALTHY/WARNING/FAILED
last_candle_time TIMESTAMPTZ,
stale_seconds BIGINT,
checked_at TIMESTAMPTZ NOT NULL,
details_json JSONB,
UNIQUE(instrument_id, interval, window_start, window_end)
```

Indexes:

- `(instrument_id, interval, checked_at DESC)`
- `(status, checked_at DESC)`

### New table: `market_data_reconciliation_run`

```sql
id UUID PRIMARY KEY,
status VARCHAR(20) NOT NULL,
exchange VARCHAR(20),
interval VARCHAR(32),
started_at TIMESTAMPTZ NOT NULL,
completed_at TIMESTAMPTZ,
checked_instruments INT NOT NULL DEFAULT 0,
gaps_found INT NOT NULL DEFAULT 0,
gaps_repaired INT NOT NULL DEFAULT 0,
error_code VARCHAR(80),
error_message VARCHAR(500)
```

Reuse current historical candle/backfill tables. Do not create another candle store.

## Backend classes

Add under `marketdata`:

- `MarketSessionCalendar`
  - `boolean isTradingDay(LocalDate date)`
  - `Instant sessionOpen(LocalDate date)`
  - `Instant sessionClose(LocalDate date)`
  - `List<Instant> expectedCandleTimes(date, interval)`
- `MarketDataQualityService`
  - detects missing candles, duplicates, stale data and session gaps.
- `MarketDataQualityRepository`
- `MarketDataReconciliationService`
  - creates bounded repair/backfill work from quality gaps.
- `MarketDataRateLimiter`
  - provider-aware pacing/backoff; no `Thread.sleep` scattered across services.
- `ProviderRequestMetrics`
  - request count, failures, throttles, latency.

Extend existing `MarketDataRepository` with bounded methods only:

- `latestCandle(instrumentId, interval)`
- `countCandles(instrumentId, interval, from, to)`
- `findMissingRangeCandidates(...)` if SQL can safely help.

## API

Add `MarketDataOperationsController`:

- `GET /api/market-data/quality?instrumentId=&interval=`
- `POST /api/market-data/quality/check`
- `POST /api/market-data/reconciliation`
- `GET /api/market-data/reconciliation/{id}`

All mutating operations require operator authentication and bounded limits.

## Frontend

Extend existing `MarketDataPage.jsx` rather than create a duplicate page.

Components:

- `MarketDataQualityCard`
- `CandleGapTable`
- `BackfillRunStatus`
- `ReconciliationRunPanel`
- `ProviderHealthBadge`

Pro Terminal: compact quality/status grid + detailed table.  
Clear Invest: summary health cards + guided “repair gaps” flow.

## Tests

- holiday/weekend/session boundary fixtures;
- 1m/3m/5m/15m/1h expected-count tests;
- stale latest candle detection;
- gap repair idempotency;
- provider 429/backoff behavior;
- reconciliation does not cross requested instrument/date bounds.

## Exit gate

Persisted NSE/BSE cash-equity history is quality-scored, recoverable and observable before scanner/backtest/execution consume it.

---

# Phase 2 — Deterministic strategy and scanner suite

**Status:** PARTIAL. Existing scanner strategies remain; no rewrite.

## Database

### `strategy_definition`

```sql
strategy_key VARCHAR(80) PRIMARY KEY,
version VARCHAR(32) NOT NULL,
enabled BOOLEAN NOT NULL DEFAULT TRUE,
trading_style VARCHAR(20) NOT NULL, -- INTRADAY/SWING
supported_intervals VARCHAR(300) NOT NULL,
minimum_candles INT NOT NULL,
requires_session_data BOOLEAN NOT NULL,
config_json JSONB NOT NULL,
updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
```

This is configuration/metadata, not executable scripting. Strategy algorithms remain compiled Java.

### Optional candidate de-duplication columns

Extend `research_scan_candidate` only if needed:

- `candidate_fingerprint VARCHAR(64)` unique per semantic setup;
- `metadata_json JSONB` for strategy-version/config snapshot.

## Backend classes

Keep existing `ScannerStrategy`, `ScannerEngine`, `ScanContext`, `ScanCandidate`, `IndicatorMath`.

Add:

- `StrategyDefinition`
- `StrategyRegistry`
  - owns compiled implementations + metadata;
  - rejects duplicate strategy names.
- `StrategyDefinitionRepository`
- `StrategyEligibilityService`
  - interval/session/history/data-quality checks before strategy execution.
- `CandidateFingerprint`
  - SHA-256 of instrument/strategy/version/interval/signal candle/levels.
- `ScannerPerformanceGuard`
  - bounded universe and deadline/cancellation guard.

Each strategy must expose metadata via `ScannerStrategyDescriptor`:

```java
record ScannerStrategyDescriptor(
    String name,
    String version,
    TradingStyle tradingStyle,
    Set<HistoricalCandleInterval> intervals,
    int minimumCandles,
    boolean sessionBound
) {}
```

## API

- `GET /api/research/strategies`
- `PATCH /api/research/strategies/{key}` — enable/disable/config only, never upload code.
- retain current scan endpoints.

## Frontend

New `ResearchScannerPage.jsx` with shared data model.

Components:

- `ScannerRunForm`
- `StrategyFilterBar`
- `RankedCandidateTable`
- `CandidateReasonChips`
- `CandidateRiskRewardPreview`
- `ScannerRunHistory`

Click candidate opens `ResearchCandidatePage.jsx` (Phase 4 orchestration content added later).

## Tests

Per strategy: positive, negative, insufficient history, future candle, stale candle, wrong interval/session, deterministic replay. Add scanner max-universe runtime test with mocked repository data.

## Exit gate

All enabled strategies are versioned, deterministic, bounded and reproducible from persisted candles/configuration.

---

# Phase 3 — Backtesting and statistical validation

**Status:** PARTIAL. Extend current `backtest` package and `BacktestingPage.jsx`.

## Database

Extend current backtest run model with a JSON snapshot if absent:

```sql
strategy_key VARCHAR(80),
strategy_version VARCHAR(32),
strategy_config_json JSONB,
fee_model_version VARCHAR(32),
slippage_model_version VARCHAR(32),
data_cutoff_at TIMESTAMPTZ,
benchmark_return NUMERIC(19,8),
max_drawdown NUMERIC(19,8),
win_rate NUMERIC(19,8),
profit_factor NUMERIC(19,8),
expectancy_r NUMERIC(19,8),
average_r NUMERIC(19,8),
turnover NUMERIC(19,4),
exposure_pct NUMERIC(19,8)
```

### `walk_forward_validation_run`

```sql
id UUID PRIMARY KEY,
strategy_key VARCHAR(80) NOT NULL,
instrument_id BIGINT NOT NULL,
interval VARCHAR(32) NOT NULL,
window_count INT NOT NULL,
train_bars INT NOT NULL,
test_bars INT NOT NULL,
step_bars INT NOT NULL,
status VARCHAR(20) NOT NULL,
config_snapshot JSONB NOT NULL,
started_at TIMESTAMPTZ NOT NULL,
completed_at TIMESTAMPTZ,
summary_json JSONB
```

### `walk_forward_window`

```sql
id BIGSERIAL PRIMARY KEY,
run_id UUID NOT NULL REFERENCES walk_forward_validation_run(id) ON DELETE CASCADE,
window_index INT NOT NULL,
train_from TIMESTAMPTZ NOT NULL,
train_to TIMESTAMPTZ NOT NULL,
test_from TIMESTAMPTZ NOT NULL,
test_to TIMESTAMPTZ NOT NULL,
metrics_json JSONB NOT NULL,
UNIQUE(run_id, window_index)
```

## Backend classes

Keep current backtest repository/service types. Add:

- `BacktestStrategyAdapter` — adapts `ScannerStrategy`/strategy signal logic to historical replay without network calls.
- `BacktestMetricsCalculator`
- `BenchmarkCalculator`
- `WalkForwardValidationService`
- `WalkForwardRepository`
- `LookaheadGuard`
  - ensures decision uses only candles at/before evaluation cutoff.
- `TradingCostModel`
  - versioned fee/slippage assumptions.
- `BacktestExportService` — CSV/JSON export, bounded.

## API

- retain `/api/backtests` and validation endpoints;
- `POST /api/backtests/walk-forward`
- `GET /api/backtests/walk-forward/{id}`
- `GET /api/backtests/{id}/export?format=csv|json`

## Frontend

Extend `BacktestingPage.jsx`:

- tabs: `Single Run`, `Holdout`, `Walk Forward`;
- `BacktestMetricGrid`;
- `EquityCurveChart`;
- `DrawdownChart`;
- `TradeDistributionPanel`;
- `AssumptionSnapshot`;
- `BenchmarkComparison`;
- `ValidationWarningBanner` (“historical results do not guarantee future performance”).

## Tests

- no look-ahead with boundary candle;
- same input -> same trades/metrics;
- cost model snapshot immutable;
- zero-trade and all-loss edge cases;
- walk-forward window separation;
- benchmark calculation fixtures.

## Exit gate

Every enabled strategy can be compared using reproducible leakage-safe historical validation with immutable assumptions.

---

# Phase 4 — Scenario simulation and regime-aware research

**Status:** IN PROGRESS. Continue from existing `research.simulation` implementation.

## Database

### `research_scenario_run`

```sql
id UUID PRIMARY KEY,
scan_run_id UUID REFERENCES research_scan_run(id),
scan_candidate_id BIGINT REFERENCES research_scan_candidate(id),
instrument_id BIGINT NOT NULL,
interval VARCHAR(32) NOT NULL,
methodology_version VARCHAR(80) NOT NULL,
status VARCHAR(20) NOT NULL,
entry_price NUMERIC(19,4) NOT NULL,
stop_price NUMERIC(19,4) NOT NULL,
target_price NUMERIC(19,4) NOT NULL,
simulations INT NOT NULL,
horizon_bars INT NOT NULL,
block_size INT NOT NULL,
seed BIGINT NOT NULL,
slippage_bps INT NOT NULL,
round_trip_cost_bps INT NOT NULL,
history_from TIMESTAMPTZ NOT NULL,
history_to TIMESTAMPTZ NOT NULL,
data_cutoff_at TIMESTAMPTZ NOT NULL,
regime VARCHAR(30),
historical_samples INT,
target_first_count INT,
stop_first_count INT,
neither_count INT,
target_probability NUMERIC(19,10),
stop_probability NUMERIC(19,10),
neither_probability NUMERIC(19,10),
expected_r NUMERIC(19,10),
p05_r NUMERIC(19,10),
p50_r NUMERIC(19,10),
p95_r NUMERIC(19,10),
started_at TIMESTAMPTZ NOT NULL,
completed_at TIMESTAMPTZ,
error_code VARCHAR(80),
error_message VARCHAR(500)
```

Indexes:

- `(scan_candidate_id, started_at DESC)`
- `(instrument_id, interval, started_at DESC)`
- `(status, started_at DESC)`

## Backend classes

Keep:

- `HistoricalScenarioSimulator`
- `ScenarioSimulationRequest`
- `ScenarioSimulationResult`
- `ScenarioSimulationService`
- `ScenarioSimulationController`

Add:

- `MarketRegime` enum: `LOW_VOL`, `NORMAL`, `HIGH_VOL`, with liquidity dimension stored separately initially.
- `RegimeClassifier`
  - rolling ATR/return-vol percentile;
  - volume ratio/liquidity bucket;
  - deterministic thresholds from config snapshot.
- `HistoricalBlock`
- `HistoricalBlockSampler`
  - never crosses trading-day boundary;
  - only samples blocks compatible with requested regime when enough samples exist;
  - deterministic fallback to broader pool with `fallbackReason` provenance.
- `ScenarioSimulationRepository`
- `ScenarioResearchOrchestrator`
  - candidate -> historical validation lookup/run -> scenario simulation.
- `ScenarioMethodologyVersion` constants.

Methodology becomes e.g. `REGIME_BLOCK_BOOTSTRAP_CONSERVATIVE_V2`.

## API

- retain `POST /api/research/simulations`;
- `GET /api/research/simulations/{id}`
- `GET /api/research/simulations?candidateId=&limit=`
- `POST /api/research/candidates/{candidateId}/analyze`
- `GET /api/research/candidates/{candidateId}/analysis`

`analyze` never places an order.

## Frontend

`ResearchCandidatePage.jsx`:

- candidate identity/strategy/reasons;
- historical validation status;
- target/stop/neither probability visualization;
- expected R + P05/P50/P95;
- regime badge and methodology details;
- cost/slippage assumptions;
- reproducibility panel (`seed`, sample count, cutoff, methodology);
- button `Review Paper Trade` only after Phase 5 risk precheck; never “Auto Buy”.

Pro Terminal: compact analytics grid/distribution chart.  
Clear Invest: guided explanation cards with identical values.

## Tests

- same seed/history/config -> identical output;
- blocks never cross session/day;
- regime filter/fallback deterministic;
- both stop+target -> stop first;
- costs reduce expected R correctly;
- persistence round-trip preserves exact seed/methodology/cutoff.

## Exit gate

Candidate analysis is fully reproducible and auditable without broker/AI authority.

---

# Phase 5 — Risk engine completion

**Status:** PARTIAL. Extend existing `RiskPolicy`, `RiskInput`, `RiskDecision`, `RiskReason`.

## Database

### `risk_policy_version`

```sql
id UUID PRIMARY KEY,
version VARCHAR(40) NOT NULL UNIQUE,
active BOOLEAN NOT NULL DEFAULT FALSE,
config_json JSONB NOT NULL,
created_by VARCHAR(120) NOT NULL,
created_at TIMESTAMPTZ NOT NULL,
activated_at TIMESTAMPTZ
```

### Extend persisted risk decision

Ensure every decision stores:

```sql
policy_version VARCHAR(40) NOT NULL,
order_intent_id UUID,
input_snapshot JSONB NOT NULL,
reasons_json JSONB NOT NULL,
decision VARCHAR(30) NOT NULL, -- APPROVED/REJECTED/APPROVAL_REQUIRED
created_at TIMESTAMPTZ NOT NULL
```

## Backend

Add `RiskRule` interface:

```java
interface RiskRule {
    String code();
    RiskRuleResult evaluate(RiskContext context);
}
```

`RiskPolicy` becomes orchestrator over ordered immutable rules, preserving current behavior.

Concrete rules:

- `EmergencyStopRule`
- `MarketSessionRule`
- `SignalFreshnessRule`
- `PriceLevelRule`
- `MinimumRewardRiskRule`
- `PerTradeRiskBudgetRule`
- `MaxOrderNotionalRule`
- `DailyLossRule`
- `DailyTradeCountRule`
- `MaxPositionsRule`
- `GrossExposureRule`
- `SymbolConcentrationRule`
- `DuplicateExposureRule`
- `CashAvailabilityRule`
- `MarketStateFreshnessRule`

Add:

- `RiskContextFactory`
- `RiskPolicyRepository`
- `RiskPolicyService`
- `PositionSizingService`
- `RiskPrecheckService`

`PositionSizingService` returns suggestion only; final submitted quantity is revalidated.

## API

- `GET /api/risk/policy`
- `POST /api/risk/precheck`
- `GET /api/risk/utilization`
- `GET /api/risk/decisions?limit=&offset=&decision=`
- operator-only versioned policy update endpoint; activation must be audited.

## Frontend

Extend `RiskPage.jsx`:

- hard-limit cards;
- utilization bars;
- emergency-stop status;
- rejection history;
- policy version/details;
- position-size calculator;
- non-overridable rule labeling.

`SimulateTradeDialog.jsx` calls risk precheck and renders exact reason codes before submission.

## Tests

Boundary tests for every rule; concurrent duplicate exposure; stale quote/account state; policy snapshot immutability; rule ordering must not alter hard-rejection result.

## Exit gate

Every executable order intent is deterministically risk-gated with immutable policy/input provenance.

---

# Phase 6 — PAPER trading accounting and portfolio engine

**Status:** PARTIAL. Extend `paper` package; do not replace `PaperTradingService` until behavior is safely decomposed.

## Database

Use/extend current paper tables. Target normalized model:

### `paper_order`

Required columns/state:

```text
id UUID
idempotency_key VARCHAR UNIQUE
correlation_id UUID
instrument_id BIGINT
side BUY/SELL
order_type MARKET/LIMIT
quantity BIGINT
limit_price NUMERIC nullable
status CREATED/RISK_REJECTED/APPROVAL_PENDING/ACCEPTED/PARTIALLY_FILLED/FILLED/CANCELLED/REJECTED
strategy_key VARCHAR
signal_id/research_candidate_id nullable
trading_style INTRADAY/SWING
risk_policy_version VARCHAR
created_at/updated_at
```

### `paper_fill`

```text
id UUID PK
order_id UUID FK
quantity BIGINT
raw_price NUMERIC(19,4)
slippage NUMERIC(19,4)
fees NUMERIC(19,4)
net_price NUMERIC(19,4)
filled_at TIMESTAMPTZ
UNIQUE(order_id, fill_sequence)
```

### `paper_position`

```text
instrument_id BIGINT + trading_style composite identity
quantity BIGINT
average_cost NUMERIC(19,4)
realized_pnl NUMERIC(19,4)
opened_at
updated_at
version BIGINT
```

### `portfolio_snapshot`

```sql
id BIGSERIAL PRIMARY KEY,
snapshot_at TIMESTAMPTZ NOT NULL,
cash NUMERIC(19,4) NOT NULL,
market_value NUMERIC(19,4) NOT NULL,
unrealized_pnl NUMERIC(19,4) NOT NULL,
realized_pnl NUMERIC(19,4) NOT NULL,
total_equity NUMERIC(19,4) NOT NULL,
gross_exposure NUMERIC(19,4) NOT NULL,
source VARCHAR(30) NOT NULL
```

Current cash ledger remains authoritative for cash movements.

## Backend decomposition

Keep `OrderCommand`, `PaperExecutionModel`, `PaperQueryService`, `PaperTradingService`, `PaperViews`.

Gradually extract:

- `PaperOrderRepository`
- `PaperFillRepository`
- `PaperPositionRepository`
- `CashLedgerRepository`
- `PaperOrderStateMachine`
- `PaperExecutionService`
- `PaperAccountingService`
- `PortfolioValuationService`
- `PaperReconciliationService`
- `PaperSessionCloseService`

State transitions must use SQL conditions/version to prevent double transitions.

## API

- keep current paper endpoints;
- `POST /api/paper/orders/{id}/cancel`
- `GET /api/paper/orders/{id}`
- `GET /api/paper/positions`
- `GET /api/paper/portfolio`
- `GET /api/paper/portfolio/equity-curve`
- admin-only `POST /api/paper/account/reset` with confirmation token/audit.

## Frontend

Extend `PaperTradesPage.jsx` and add:

- `PositionsPage.jsx`
- `PortfolioPage.jsx`

Components:

- `OrderLifecycleTimeline`
- `FillBreakdown`
- `PositionTable`
- `PnLSummary`
- `EquityCurveChart`
- `CostBreakdown`
- `ReconciliationStatus`

## Tests

- idempotent replay;
- rejected order no cash mutation;
- partial fill accounting;
- buy then sell realized P&L;
- restart reconciliation;
- concurrent order state transition;
- intraday session close behavior.

## Exit gate

Cash + fills + positions + portfolio equity reconcile exactly.

---

# Phase 7 — Approval policy and operator workflow

**Status:** NOT STARTED.

## Database

### `execution_policy`

```sql
id UUID PRIMARY KEY,
name VARCHAR(100) NOT NULL,
mode VARCHAR(10) NOT NULL, -- PAPER/LIVE
policy_type VARCHAR(10) NOT NULL, -- MANUAL/AUTO/HYBRID
strategy_key VARCHAR(80),
trading_style VARCHAR(20),
max_order_notional NUMERIC(19,4),
max_daily_trades INT,
max_gross_exposure NUMERIC(19,4),
max_daily_loss NUMERIC(19,4),
allowed_symbols JSONB,
denied_symbols JSONB,
allowed_sessions JSONB,
active BOOLEAN NOT NULL,
version BIGINT NOT NULL,
created_by VARCHAR(120) NOT NULL,
created_at TIMESTAMPTZ NOT NULL
```

### `order_approval`

```sql
id UUID PRIMARY KEY,
order_intent_id UUID NOT NULL,
policy_id UUID NOT NULL REFERENCES execution_policy(id),
policy_version BIGINT NOT NULL,
status VARCHAR(20) NOT NULL, -- PENDING/APPROVED/REJECTED/EXPIRED/CANCELLED/EXECUTED
requested_at TIMESTAMPTZ NOT NULL,
expires_at TIMESTAMPTZ NOT NULL,
decided_at TIMESTAMPTZ,
decided_by VARCHAR(120),
reason VARCHAR(500),
risk_decision_id UUID NOT NULL,
version BIGINT NOT NULL DEFAULT 0,
UNIQUE(order_intent_id)
```

## Backend

Package `approval`:

- `ExecutionPolicyType`
- `ExecutionPolicy`
- `ExecutionPolicyRepository`
- `ExecutionPolicyService`
- `ApprovalDecision`
- `OrderApprovalRepository`
- `OrderApprovalService`
- `ApprovalEligibilityService`
- `PreExecutionRevalidationService`

Flow:

```text
OrderIntent -> hard RiskPolicy
  REJECTED -> terminal reject
  APPROVED -> policy evaluation
      MANUAL -> approval queue
      AUTO -> immediate pre-execution revalidation
      HYBRID -> auto only if hybrid thresholds pass, else manual
 -> final risk/quote/account revalidation
 -> PAPER execution (LIVE gateway still disabled)
```

## API

- `GET /api/execution-policies`
- `POST /api/execution-policies`
- `PATCH /api/execution-policies/{id}`
- `GET /api/approvals?status=PENDING`
- `POST /api/approvals/{id}/approve`
- `POST /api/approvals/{id}/reject`
- `POST /api/approvals/{id}/cancel`

All decisions require authenticated operator identity and optimistic version.

## Frontend

Add `ApprovalsPage.jsx` and settings policy section.

Components:

- `ApprovalQueue`
- `ApprovalOrderSummary`
- `RiskSnapshotPanel`
- `ApprovalCountdown`
- `ApproveRejectControls`
- `PolicyEditor`
- `PolicyScopeChips`

Hard rejection controls are never shown as approvable.

## Tests

Expiry race, double approval, concurrent approval/reject, policy change after request, revalidation failure after approval, AUTO/HYBRID boundaries.

## Exit gate

MANUAL/AUTO/HYBRID are concurrency-safe and cannot bypass risk.

---

# Phase 8 — Portfolio, analytics, alerts and operational backend

**Status:** PARTIAL.

## Database

### `notification`

```sql
id UUID PRIMARY KEY,
type VARCHAR(40) NOT NULL,
severity VARCHAR(20) NOT NULL,
source VARCHAR(40) NOT NULL,
title VARCHAR(160) NOT NULL,
message VARCHAR(1000) NOT NULL,
correlation_id UUID,
entity_type VARCHAR(40),
entity_id VARCHAR(100),
created_at TIMESTAMPTZ NOT NULL,
read_at TIMESTAMPTZ,
acknowledged_by VARCHAR(120)
```

### `operational_health_snapshot`

```sql
id BIGSERIAL PRIMARY KEY,
component VARCHAR(40) NOT NULL,
status VARCHAR(20) NOT NULL,
details_json JSONB NOT NULL,
observed_at TIMESTAMPTZ NOT NULL
```

Portfolio snapshots come from Phase 6.

## Backend

Packages:

- `portfolio.PortfolioQueryService`
- `analytics.PerformanceAnalyticsService`
- `analytics.StrategyPerformanceService`
- `analytics.DrawdownCalculator`
- `notification.NotificationService`
- `notification.NotificationRepository`
- `notification.OperationalAlertService`
- `audit.AuditQueryService`

Scheduled detectors:

- stale market data;
- failed reconciliation;
- emergency stop activation;
- broker/provider outage (when applicable);
- unexplained ledger difference;
- failed scheduled ingestion/backfill.

## API

- `GET /api/portfolio/summary`
- `GET /api/portfolio/performance?from=&to=`
- `GET /api/analytics/strategies`
- `GET /api/analytics/research`
- `GET /api/operations/health`
- `GET /api/notifications`
- `POST /api/notifications/{id}/read`
- `GET /api/audit?correlationId=&entityType=&from=&to=&limit=&offset=`
- `GET /api/audit/export`

## Frontend

Add:

- `PortfolioPage.jsx`
- `OperationsPage.jsx`
- `NotificationsPanel`
- `AuditPage.jsx`

Charts: equity, drawdown, strategy return, win/loss and risk utilization. Pro Terminal may place these in dense panels; Clear Invest uses guided cards.

## Exit gate

Operator can diagnose system and trading state without SQL/database access.

---

# Phase 9 — Complete dual frontend experiences

**Status:** IN PROGRESS.

## Architecture

Do not fork the entire React app. Use shared route/data components plus experience-specific compositions.

### Experience model

Keep current selector/context; normalize to:

```js
const EXPERIENCE = {
  PRO_TERMINAL: 'pro-terminal',
  CLEAR_INVEST: 'clear-invest'
}
```

Add `ExperienceContext` responsibility only for presentation choice, never trading behavior.

### Composition pattern

```text
Page route
  -> shared page controller/hook
  -> ExperienceBoundary
       -> ProTerminalXView
       -> ClearInvestXView
```

Example:

```text
ResearchScannerPage
├── useResearchScannerController
├── ProTerminalScannerView
└── ClearInvestScannerView
```

## Required pages

Existing pages to evolve:

- `OverviewPage.jsx`
- `SignalsPage.jsx`
- `PaperTradesPage.jsx`
- `RiskPage.jsx`
- `InstrumentsPage.jsx`
- `MarketDataPage.jsx`
- `BacktestingPage.jsx`

New pages:

- `ResearchScannerPage.jsx`
- `ResearchCandidatePage.jsx`
- `ApprovalsPage.jsx`
- `PositionsPage.jsx`
- `PortfolioPage.jsx`
- `NewsPage.jsx`
- `OperationsPage.jsx`
- `AuditPage.jsx`
- `SettingsPage.jsx`

## Shared design system

Add semantic tokens, not per-page magic colors:

```text
--surface-app
--surface-panel
--surface-elevated
--text-primary
--text-secondary
--border-subtle
--positive
--negative
--warning
--info
--focus-ring
--risk-hard-stop
--mode-paper
--mode-live
```

Pro Terminal: compact spacing/dense tables/advanced charts.  
Clear Invest: larger spacing/guided hierarchy/accessibility.  
No neumorphism.

## Navigation

Desktop and mobile route inventory must include every mandatory screen. Mobile bottom nav remains limited to primary actions; secondary routes remain in drawer/more menu.

## State

React Query is the server-state source. Do not duplicate fetched entities in `localStorage`. Persist only UI preferences such as experience/theme; auth/session rules defined Phase 10.

## Testing

- component tests for shared controllers;
- Playwright/Cypress E2E (choose one and standardize) for both experiences;
- axe accessibility tests;
- responsive viewports 320, 375, 390, 768, 1024, 1440;
- visual snapshots for both experiences;
- experience switch preserves route/form/query state.

## Exit gate

Every mandatory flow exists and works in both designs with shared business logic and accessible responsive behavior.

---

# Phase 10 — Authentication, authorization and security hardening

**Status:** PARTIAL.

## Target security model

Current operator/API-key mechanisms must evolve to durable operator identity before production multi-user/LIVE operation.

## Database

### `app_user`

```sql
id UUID PRIMARY KEY,
email VARCHAR(254) NOT NULL UNIQUE,
display_name VARCHAR(120),
status VARCHAR(20) NOT NULL,
created_at TIMESTAMPTZ NOT NULL,
last_login_at TIMESTAMPTZ
```

### `app_role`

```text
id BIGSERIAL PK
name VARCHAR UNIQUE -- ADMIN/OPERATOR/VIEWER
```

### `app_user_role`

```text
user_id UUID FK
role_id BIGINT FK
PRIMARY KEY(user_id, role_id)
```

### `user_preference`

```sql
user_id UUID PRIMARY KEY REFERENCES app_user(id),
experience VARCHAR(30) NOT NULL,
theme VARCHAR(20),
updated_at TIMESTAMPTZ NOT NULL
```

Do not store passwords unless the selected identity architecture requires local credentials. Preferred production direction: external/OIDC identity with server session/JWT validation; decide provider before implementation.

## Backend

Package `security`:

- `CurrentUser`
- `AuthenticationFilter` / Spring Security configuration
- `AuthorizationService`
- `OperatorPrincipal`
- `Role`
- `SecurityAuditService`

Endpoint authorization:

- VIEWER: research/read-only dashboards;
- OPERATOR: PAPER order/approval actions;
- ADMIN: policies, emergency stop, account reset, infrastructure-sensitive settings;
- LIVE actions later require explicit additional LIVE permission/activation state.

Security controls:

- rate limits on auth/mutating endpoints;
- CSRF strategy appropriate to chosen auth method;
- CORS exact allowlist;
- secure headers;
- request-size limits;
- SSRF protections retained for external fetchers;
- secrets only server-side/AWS Secrets Manager later;
- log redaction for tokens/account identifiers.

## Frontend

Add:

- `AuthContext` only if not fully handled by cookie/session;
- `ProtectedRoute`;
- `PermissionGate` for UX only (backend remains authority);
- user/profile section in `SettingsPage`;
- sync `experience` preference to profile when authenticated while retaining safe local fallback.

## Tests

Role matrix API tests, IDOR tests, CORS/CSRF tests, token expiry, forbidden escalation, log redaction and dependency/security scan in CI.

## Exit gate

Every mutating API has authenticated identity, explicit authorization and auditable actor attribution.

---

# Phase 11 — Mobile/PWA/platform validation

**Status:** PARTIAL.

## Web/PWA

Keep current manifest/mobile CI. Add:

- production PNG icons 192/512 and maskable asset;
- offline shell only for non-trading static navigation;
- never queue order/approval requests offline;
- network/offline banner;
- stale-data indicators;
- installability checks over HTTPS production/staging.

## Android

If TWA remains selected:

```text
android/
├── app
├── assetlinks.json deployment contract
└── release signing documentation
```

Requirements:

- verified Digital Asset Links;
- no browser toolbar when verification succeeds;
- Play signing/release configuration;
- deep-link tests;
- physical-device validation.

## iOS

If native wrapper/PWA distribution is selected, document exact chosen mechanism before code. Do not claim App Store readiness from PWA metadata alone.

## UI validation

Test both Pro Terminal and Clear Invest on supported mobile viewports and physical Android/iOS where applicable.

## Exit gate

Production domain installation/navigation behaves as an app, with no offline trade execution and no unsafe state loss.

---

# Phase 12 — AWS production infrastructure and deployment

**Status:** EXTERNAL WAIT for account activation. Implementation review continues.

## Decision gate

Choose exactly one initial stack before apply:

1. `infra/terraform` — managed VPC/ECS/RDS/ALB/ECR/ACM/Route53/CloudWatch/Secrets Manager;
2. `infra/terraform-budget` — single ARM64 EC2 + Docker Compose + encrypted EBS + S3 backups.

Do not provision both.

## Preferred production resource contracts

If full stack selected:

- VPC with public/private subnets;
- ALB public HTTPS only;
- backend/frontend ECS services private where applicable;
- PostgreSQL RDS private, encrypted, deletion protection;
- ECR immutable commit-tag images;
- Secrets Manager for DB/operator/broker/news secrets;
- CloudWatch logs/alarms;
- Route53 + ACM;
- GitHub OIDC deployment role scoped to repository/branch;
- AWS Budget alerts.

## Terraform modules/files

Refactor only if useful, preserving current working configuration:

```text
infra/terraform/
├── providers.tf
├── variables.tf
├── network.tf
├── security.tf
├── database.tf
├── compute.tf
├── load_balancer.tf
├── dns_tls.tf
├── secrets.tf
├── observability.tf
├── oidc.tf
├── budget.tf
└── outputs.tf
```

## Deployment pipeline

`deploy-paper-aws.yml`:

1. baseline CI;
2. build immutable images;
3. assume AWS role via OIDC;
4. push ECR;
5. run DB migration as controlled deployment step/startup gate;
6. deploy PAPER services;
7. smoke health/read-only endpoints;
8. verify `TRADING_MODE=PAPER`;
9. rollback to prior image on failed health gate.

No AWS apply until account is active and Terraform plan/cost/security review is approved.

## Exit gate

Staging and production PAPER workloads deploy reproducibly with TLS, backups, secrets, monitoring and rollback.

---

# Phase 13 — Observability, reliability and production hardening

**Status:** PARTIAL.

## Backend observability

Use Micrometer/Spring Actuator where compatible.

Metrics:

- API latency/error rate;
- DB pool saturation;
- market-data provider latency/errors/throttles;
- candle freshness/gaps;
- scanner duration/candidate count;
- backtest/simulation duration;
- risk approvals/rejections by reason;
- paper order/fill counts;
- reconciliation differences;
- approval queue age;
- news ingestion failures;
- JVM memory/GC/thread metrics.

## Structured logs

Every trading-impacting path logs:

- correlationId;
- orderIntentId/orderId if applicable;
- strategy key/version;
- policy version;
- outcome/status;
- never token/secret content.

## Reliability tests

Add CI/nightly suites:

- load test read APIs/scanner bounded universe;
- restart tests during pending PAPER/approval flow;
- DB connectivity interruption;
- provider timeout/429/5xx;
- stale market data;
- duplicate requests;
- migration-from-prior-version test;
- backup/restore drill in staging after AWS available.

## Operations

Add runbooks under `docs/runbooks/`:

- emergency stop;
- market data outage;
- database recovery;
- bad deployment rollback;
- ledger reconciliation failure;
- broker outage (Phase 15);
- credential rotation.

## Exit gate

No critical/high unresolved production defect; failure scenarios are observable and recoverable with documented procedures.

---

# Phase 14 — Production PAPER/research V1 launch

**Status:** NOT STARTED.

This is the first finite production launch milestone.

## Release artifacts

- versioned release tag;
- migration manifest;
- configuration manifest (no secret values);
- test evidence;
- security checklist;
- backup/restore evidence;
- known limitations;
- rollback version;
- operator runbook links.

## Launch checklist

1. All mandatory Phase 0–13 gates required for PAPER are green.
2. AWS production environment healthy.
3. `TRADING_MODE=PAPER` verified in runtime and UI.
4. LIVE broker submission implementation either absent or hard-disabled.
5. Market data provider configured and small-range verification passed.
6. Database backup verified.
7. Alert delivery verified.
8. Dual-design critical E2E flows pass.
9. Paper order -> risk -> approval policy (if enabled) -> fill -> ledger -> position -> analytics reconciles.
10. Research candidate -> backtest -> scenario -> paper review path passes.

## Smoke workflow

Automate `ProductionPaperSmokeTest` / external smoke script:

- health;
- auth;
- instrument search;
- historical candle read;
- scanner run;
- candidate analysis;
- PAPER risk precheck;
- small PAPER order;
- verify fill/ledger/position/audit;
- verify notification/metrics.

## Exit gate

Product is publicly/privately available in production for the intended PAPER/research audience with operational support and no real-money execution.

---

# Phase 15 — Optional Angel One LIVE broker execution and controlled pilot

**Status:** NOT STARTED; separately gated. This phase must not be interpreted as permission to place real-money orders.

## Regulatory/provider prerequisite

Before implementation activation, re-check current Angel One SmartAPI capabilities/rate limits/order semantics and applicable SEBI/exchange/broker retail algorithmic-trading requirements using current official sources. Record review date and decisions in `docs/live/REGULATORY_AND_BROKER_REVIEW.md`.

## Database

### `live_order_intent`

```sql
id UUID PRIMARY KEY,
client_order_id VARCHAR(80) NOT NULL UNIQUE,
request_hash VARCHAR(64) NOT NULL,
instrument_id BIGINT NOT NULL,
side VARCHAR(10) NOT NULL,
order_type VARCHAR(20) NOT NULL,
quantity BIGINT NOT NULL,
limit_price NUMERIC(19,4),
status VARCHAR(30) NOT NULL,
risk_decision_id UUID NOT NULL,
approval_id UUID,
policy_snapshot JSONB NOT NULL,
created_by VARCHAR(120) NOT NULL,
created_at TIMESTAMPTZ NOT NULL,
submitted_at TIMESTAMPTZ,
version BIGINT NOT NULL DEFAULT 0
```

### `broker_order`

```sql
id UUID PRIMARY KEY,
live_order_intent_id UUID NOT NULL REFERENCES live_order_intent(id),
broker VARCHAR(30) NOT NULL,
broker_order_id VARCHAR(120),
status VARCHAR(30) NOT NULL,
raw_status VARCHAR(80),
submitted_at TIMESTAMPTZ,
last_reconciled_at TIMESTAMPTZ,
response_metadata JSONB,
UNIQUE(broker, broker_order_id)
```

### `broker_fill`

```text
id UUID PK
broker_order_id UUID FK
broker_trade_id VARCHAR UNIQUE
quantity BIGINT
price NUMERIC
fees NUMERIC nullable
filled_at TIMESTAMPTZ
raw_payload_hash VARCHAR
```

## Backend broker boundary

Package `broker`:

```java
interface BrokerOrderGateway {
    BrokerOrderSubmission submit(BrokerOrderCommand command);
    BrokerOrderStatus getOrder(String brokerOrderId);
    BrokerCancelResult cancel(String brokerOrderId);
    BrokerAccountSnapshot accountSnapshot();
    List<BrokerPosition> positions();
}
```

Implementations:

- `broker.mock.MockBrokerOrderGateway` first;
- `broker.angelone.AngelOneBrokerOrderGateway` later.

Services:

- `LiveOrderIntentService`
- `LiveExecutionGuard`
- `BrokerOrderService`
- `BrokerReconciliationService`
- `BrokerSessionService`
- `BrokerAccountSyncService`
- `LiveEmergencyStopService`

## LIVE state machine

```text
CREATED
 -> RISK_REJECTED (terminal)
 -> APPROVAL_PENDING
 -> READY_FOR_SUBMISSION
 -> SUBMITTING
 -> SUBMITTED
 -> PARTIALLY_FILLED
 -> FILLED
 -> CANCEL_PENDING -> CANCELLED
 -> BROKER_REJECTED
 -> UNKNOWN (must reconcile; never blind retry)
```

`UNKNOWN` outcome is critical: retries use same `client_order_id` and reconciliation before any resubmission.

## Hard activation controls

All must be true before real submission:

- runtime mode `LIVE`;
- explicit server-side `LIVE_ORDER_SUBMISSION_ENABLED=true`;
- operator has LIVE permission;
- broker credentials/session healthy;
- compliance/provider review current;
- emergency stop false;
- fresh quote/account/position state;
- hard risk approved;
- required approval complete;
- production pilot allowlist/notional limits active.

Any false/missing state fails closed.

## API

LIVE endpoints are separate from PAPER:

- `GET /api/live/status`
- `GET /api/live/account`
- `GET /api/live/orders`
- `POST /api/live/orders` only after activation gate;
- `POST /api/live/orders/{id}/cancel`
- `POST /api/live/emergency-stop`

## Frontend

Never silently reuse PAPER visuals for LIVE.

Add persistent LIVE banner and distinct confirmation:

- account/broker health;
- LIVE order review;
- risk snapshot;
- approval state;
- notional/quantity;
- explicit “Real money” wording;
- emergency stop control for authorized users;
- broker order/reconciliation status.

## Pilot sequence

1. mock gateway tests;
2. read-only Angel One connectivity;
3. shadow reconciliation without submission;
4. production LIVE code deployed hard-disabled;
5. emergency-stop drill;
6. separately authorized tiny MANUAL order pilot;
7. reconcile broker/order/fill/account;
8. only then consider bounded expansion.

## Exit gate

Real-money trading is enabled only after explicit go-live approval and a successful controlled pilot. AUTO LIVE remains separately configurable and should not be enabled merely because manual LIVE works.

---

# 16. Cross-phase API/data invariants

1. `instrument_id` is internal durable identity; broker token/symbol is provider metadata and may version/change.
2. Research candidates are non-executable objects.
3. Scenario/backtest results never directly create orders.
4. Every order starts as an immutable `OrderIntent`/command with idempotency identity.
5. Hard risk rejection is terminal for that intent version.
6. Manual approval never freezes market/risk state; full revalidation occurs before execution.
7. PAPER and LIVE accounting are distinguishable in persistence/API/UI.
8. No frontend-calculated P&L/risk number is authoritative; frontend displays backend-calculated values.
9. All list APIs use bounded pagination.
10. All data used for a trading-impacting decision has freshness/cutoff provenance.
11. No endpoint may expose broker/API keys or raw secret-bearing provider responses.

---

# 17. Implementation order from current repository state

The immediate coding sequence is fixed unless a blocker makes a later independent task preferable:

```text
A. Finish Phase 4 regime/provenance persistence
B. Phase 5 risk-rule completion
C. Phase 6 PAPER accounting/portfolio reconciliation
D. Phase 7 approval policies
E. Phase 8 analytics/operations backend
F. Phase 9 complete dual-design UI against those APIs
G. Phase 10 security identity/authorization hardening
H. Phase 11 mobile/platform validation
I. Phase 12 AWS provisioning when account becomes active (parallel when possible)
J. Phase 13 reliability/observability hardening
K. Phase 14 production PAPER/research launch
L. Phase 15 separately gated LIVE pilot
```

Phase 1/2/3 remaining production-readiness gaps are implemented before any dependent launch gate and may be interleaved with A–F when they unblock research accuracy.

---

# 18. PR contract

Every implementation PR must state:

- Master phase and low-level work items implemented.
- Tables/migrations changed.
- APIs changed.
- Backend classes added/modified.
- Frontend pages/components/hooks added/modified.
- Tests added and actual CI result.
- Safety impact: PAPER/LIVE/risk/AI authority.
- Remaining items in that phase.

A phase is marked `DONE` in `MASTER_IMPLEMENTATION_PLAN.md` only after its exit gate is demonstrated, not merely because code exists.
