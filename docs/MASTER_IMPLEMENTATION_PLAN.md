# AI Trading System — Master Implementation Plan

> **Status:** AUTHORITATIVE SOURCE OF TRUTH  
> **Applies to:** `operationReact/AiTradingSystem`  
> **Integration branch:** `dev`  
> **Last reconciled:** 2026-10-28  
> **Target:** finite end-to-end delivery from the current repository state through production launch, followed by a separately gated real-money LIVE pilot.

---

## 1. Purpose and authority

This document is the single implementation source of truth for the AI Trading System.

Historical phase documents, issues, PR descriptions, design notes and implementation plans remain useful supporting references, but **this file controls sequencing, scope, completion status and launch gates** whenever there is a conflict.

Future implementation PRs must:

1. reference the phase and work item from this plan;
2. update the relevant status/checklist in this file when scope or completion changes materially;
3. not create an open-ended new phase unless the product scope itself is explicitly changed;
4. preserve the safety boundary that deterministic risk controls cannot be bypassed by AI, news, approvals or UI state;
5. keep LIVE broker submission disabled until the explicit LIVE activation gate in this plan is reached.

The implementation is complete when all **mandatory** phases through the intended launch milestone are complete and their exit gates are satisfied. Optional/future items do not block V1 launch unless they are promoted into mandatory scope by an explicit product decision.

---

## 2. Product definition

The product is an Indian cash-equity trading research, simulation, paper-trading and eventually broker-connected execution platform with:

- deterministic market-data ingestion and instrument identity;
- deterministic research/scanner strategies;
- historical backtesting and out-of-sample validation;
- scenario simulation and regime-aware research;
- deterministic risk enforcement;
- durable PAPER order/fill/ledger/position accounting;
- optional news intelligence as untrusted research context;
- dual frontend experiences: **Pro Terminal** and **Clear Invest**;
- responsive desktop/mobile web and installable PWA behavior;
- production AWS deployment and operational monitoring;
- optional Angel One LIVE execution only after a separate hard launch gate.

### Non-negotiable architecture rule

The authoritative execution path is:

`market/instrument data -> deterministic strategy/research -> deterministic risk -> explicit execution policy -> PAPER or gated broker gateway`

AI/news may enrich research and explanations. They may **never** directly authorize an order, override hard risk rejection, bypass the emergency stop, or activate LIVE trading.

---

## 3. Current repository baseline — reconciled 2026-10-28

The following capabilities are already implemented or substantially implemented on `dev` and must be extended rather than rebuilt.

### 3.1 Backend/platform baseline — implemented

- Java 21 / Spring Boot backend.
- PostgreSQL persistence with Flyway forward-only migrations.
- Docker image build.
- CI baseline covering Java/PostgreSQL Testcontainers, frontend production build, Docker images and Terraform validation.
- Health/API configuration foundations.
- Audit service and persisted audit/event integrity coverage.
- PAPER-only execution mode guard and execution-mode APIs.

### 3.2 Instrument and market data — implemented

- versioned instrument master/catalogue;
- active instrument search and catalogue status;
- Angel One SmartAPI provider boundary;
- historical OHLCV ingestion;
- interval-specific bounded requests;
- validation of candle payloads/OHLC/volume/timestamps;
- resumable backfill runs/checkpoints;
- restart recovery and idempotent candle persistence;
- paginated candle querying;
- historical candle access for backtest/research.

### 3.3 Strategies, research and simulation — implemented baseline

- deterministic `SMA_TREND_VOLUME_V1` strategy/signals;
- persisted signal candidates and reasons;
- deterministic scanner SPI/engine;
- stale/future candle rejection;
- `VOLUME_BREAKOUT_V1`;
- `MA_RECLAIM_V1`;
- `OPENING_RANGE_BREAKOUT_V1`;
- `VWAP_MOMENTUM_V1`;
- `RSI_REVERSAL_V1`;
- bounded active NSE/BSE cash-equity research universe;
- persisted scan-run/candidate provenance;
- deterministic candidate ranking API;
- historical scenario simulator using seeded contiguous block/bootstrap sampling;
- target-first / stop-first / neither probabilities;
- expected-R and P05/P50/P95 outcomes;
- explicit slippage and transaction-cost inputs;
- conservative same-bar ambiguity behavior.

### 3.4 Backtesting/validation — implemented

- bounded offline PAPER-only backtesting;
- next-candle-open execution assumptions;
- fees/slippage assumptions;
- persisted backtest runs/trades;
- training/holdout out-of-sample validation;
- persisted validation comparison;
- backend APIs and frontend Backtesting page.

### 3.5 Risk/PAPER execution — substantially implemented

- deterministic `RiskPolicy`;
- emergency stop behavior;
- signal freshness validation;
- daily loss/drawdown limits;
- stop/target validation;
- per-trade risk budget;
- max-position constraints;
- held-symbol/cash validation;
- PAPER order persistence;
- fills, cash ledger and risk decisions;
- idempotency/replay integrity tests;
- rejected-order no-ledger-mutation tests;
- exactly-once audit integrity coverage.

### 3.6 News intelligence — implemented baseline

- provenance-aware RSS ingestion;
- registered/fixed providers;
- bounded fetch/parser behavior;
- URL/security guardrails;
- deduplication and retention;
- optional Gemini summarization with explicit opt-in;
- summaries treated as non-authoritative research only;
- bounded read API and bearer-token regression coverage;
- provider/summarization failure isolation.

### 3.7 Frontend — implemented baseline

Current React/Vite UI already contains:

- Overview;
- Signals;
- Paper Trades;
- Risk;
- Instruments;
- Market Data;
- Backtesting;
- shared shell/navigation;
- chart component(s);
- simulated-trade dialog;
- mobile bottom navigation and safe-area work;
- installable PWA metadata and CI validation;
- initial **Pro Terminal / Clear Invest** experience selector;
- first Clear Invest dashboard composition.

The dual-design migration is **not yet complete** across every screen.

### 3.8 Infrastructure — code prepared, provisioning pending

Two Terraform approaches exist:

1. full AWS architecture (`infra/terraform`), including production-oriented managed resources;
2. budget-oriented single-instance architecture (`infra/terraform-budget`).

Deployment workflows and cost/budget safeguards exist, but infrastructure has intentionally not been provisioned.

**Current external dependency:** AWS account activation/details are pending. This must **not block application implementation**. AWS-specific apply/verification work begins when the account is ready; all code, security, tests, Docker, environment contracts and Terraform review can continue meanwhile.

---

# 4. Finite delivery roadmap

There are **16 fixed phases (0–15)**. Phases may overlap where dependencies allow, but no mandatory phase may be skipped. New work should be fitted into these phases unless product scope changes.

Status values:

- `DONE` — exit gate satisfied.
- `IN PROGRESS` — active implementation exists but exit gate is incomplete.
- `PARTIAL` — substantial baseline exists; defined gaps remain.
- `NOT STARTED` — planned work remains.
- `EXTERNAL WAIT` — implementation can continue, but final gate depends on an external account/provider.

---

## Phase 0 — Architecture, safety and CI foundation

**Status: DONE / ongoing governance**

### Mandatory scope

- Java 21/Spring Boot + PostgreSQL/Flyway foundation.
- React/Vite frontend foundation.
- Docker backend/frontend images.
- CI for backend tests, frontend build, Docker build and Terraform validation.
- PAPER default mode.
- fail-closed execution-mode guard.
- audit conventions and correlation/idempotency principles.
- no secret material in frontend or source control.

### Remaining governance

- every new PR must preserve baseline CI;
- migrations remain forward-only;
- no LIVE activation through configuration drift.

### Exit gate

Baseline validation workflow green on `dev`; PAPER default preserved.

---

## Phase 1 — Instrument master and market-data production readiness

**Status: PARTIAL**

### Already implemented

Versioned instruments, catalogue import/search, Angel One provider boundary, historical candle ingestion, backfills, checkpoints, validation and persistence.

### Remaining mandatory work

- production provider contract audit against current Angel One API documentation;
- explicit rate-limit/backoff policy and metrics;
- market calendar/session utility shared by scanner/backtest/execution;
- stale/incomplete/gapped-candle detection;
- data-quality status per instrument/interval;
- reconciliation job for missing market-data windows;
- operational API/dashboard for failed/partial backfills;
- production-small-range provider verification after credentials/environment are available.

### Exit gate

For the mandatory NSE/BSE cash-equity universe, instrument identity and persisted candle history are reproducible, quality-checked, observable and recoverable.

---

## Phase 2 — Deterministic strategy and scanner suite

**Status: PARTIAL**

### Already implemented

SMA trend/volume, volume breakout, MA reclaim, opening-range breakout, rolling VWAP momentum and RSI reversal; scanner engine; deterministic ranking; stale/future rejection; bounded NSE/BSE research universe; persisted provenance.

### Remaining mandatory work

- formal strategy metadata registry/version contract;
- explicit supported interval/session constraints per strategy;
- shared indicator math validation and numerical boundary tests;
- duplicate/overlapping candidate suppression policy;
- per-strategy enable/disable configuration;
- strategy-specific minimum history/data-quality gate;
- deterministic fixtures covering positive, negative and edge conditions for every strategy;
- scanner performance tests over configured maximum universe;
- strategy/result audit documentation.

### Exit gate

Given the same persisted candle snapshot and configuration, every strategy and scan produces the same result, within bounded runtime, with versioned explanations and no external execution side effects.

---

## Phase 3 — Backtesting and statistical validation

**Status: PARTIAL**

### Already implemented

Persisted offline backtests, fees/slippage, conservative intrabar resolution, training/holdout validation and UI.

### Remaining mandatory work

- strategy-neutral backtest contract so scanner strategies can be validated consistently;
- walk-forward validation support;
- benchmark comparison (e.g. underlying buy/hold or declared benchmark where appropriate);
- expanded metrics: CAGR/period return as applicable, max drawdown, win rate, expectancy, profit factor, average R, exposure, consecutive losses, turnover;
- deterministic fee/slippage version snapshots;
- session-aware/gap-aware validation;
- validation against look-ahead/data leakage;
- backtest result export and immutable provenance snapshot;
- regression fixtures proving repeatability.

### Exit gate

Every enabled production research strategy can be evaluated on reproducible historical data with leakage-safe methodology and persisted assumptions/results.

---

## Phase 4 — Scenario simulation and regime-aware research

**Status: IN PROGRESS**

### Already implemented

Seeded historical block/bootstrap simulator, bounded paths/horizon/block size, target/stop/neither probabilities, expected-R, R percentiles, transaction costs/slippage and conservative same-bar stop-first handling.

### Remaining mandatory work

- volatility regime classifier;
- volume/liquidity regime signal;
- session/day boundary-safe contiguous block sampling;
- regime-matched block pool/fallback behavior;
- methodology version bump and reproducibility tests;
- persist scenario-run request/result/provenance;
- link simulations to scan candidate/run where applicable;
- historical cutoff and source-sample provenance;
- failure/status lifecycle for long simulations;
- candidate -> backtest -> scenario research orchestration;
- API retrieval/history endpoints;
- dual-design UI integration later in Phase 9.

### Exit gate

A research candidate can be reproduced from persisted market data through backtest and regime-aware scenario analysis with immutable methodology/seed/cutoff/cost provenance.

---

## Phase 5 — Risk engine completion

**Status: PARTIAL**

### Already implemented

Core deterministic risk policy, emergency stop, freshness, daily-loss controls, price-level checks, per-trade risk budget, positions, cash and rejection audit integrity.

### Remaining mandatory work

- minimum reward:risk threshold;
- max order notional;
- max gross portfolio exposure;
- max trades per day;
- market-session gate;
- duplicate pending exposure/order-intent protection;
- per-symbol concentration limit;
- configurable strategy/style limits;
- deterministic position-sizing suggestion using risk/share, capital and configured caps;
- hard distinction between non-overridable rejection and approval-required condition;
- persisted risk-policy version/snapshot with each order decision;
- boundary/concurrency tests for all rules;
- fail-closed behavior when required market/risk/account state is stale or missing.

### Exit gate

No order can reach an execution gateway unless the same immutable order intent has passed all hard deterministic risk rules against fresh account/market state.

---

## Phase 6 — PAPER trading accounting and portfolio engine

**Status: PARTIAL**

### Already implemented

PAPER orders, fills, risk decisions, idempotency, cash-ledger integrity, audit events and UI baseline.

### Remaining mandatory work

- explicit order lifecycle state machine;
- MARKET/LIMIT support only where deterministic simulation semantics are defined;
- pending/cancel/reject/partial-fill simulation semantics where applicable;
- position aggregation and cost basis;
- realized/unrealized P&L;
- charges/fees/slippage attribution;
- holdings vs intraday positions distinction;
- end-of-session behavior for intraday PAPER positions;
- cash/ledger/order/fill/position reconciliation job;
- portfolio snapshots/equity curve;
- strategy-level attribution;
- PAPER reset/reseed administrative workflow with audit;
- restart/concurrency reconciliation tests.

### Exit gate

Orders, fills, cash, positions and P&L reconcile exactly and remain correct across idempotent replay, process restart and rejection/failure scenarios.

---

## Phase 7 — Approval policy and operator workflow

**Status: NOT STARTED / design requirements exist in issue #32**

### Mandatory scope

Implement configurable policies without weakening hard risk controls:

- `MANUAL`;
- `AUTO`;
- `HYBRID`.

Policy dimensions:

- PAPER/LIVE mode;
- strategy;
- INTRADAY/SWING style;
- per-order notional;
- daily trade count;
- symbol allowlist/denylist;
- permitted sessions;
- exposure/daily-loss limits.

Workflow:

- approval queue;
- approve/reject/cancel;
- expiry;
- authenticated operator identity;
- policy version/snapshot;
- immutable decision audit;
- duplicate-approval/submission protection;
- full revalidation of quote/risk/cash/holdings/emergency-stop immediately before execution.

### Exit gate

All three policies are deterministic, audited, concurrency-safe and incapable of overriding hard risk rejection. PAPER is the only executable mode at this stage.

---

## Phase 8 — Portfolio, analytics, alerts and operational backend

**Status: PARTIAL**

### Mandatory scope

Backend APIs/services for:

- portfolio/account summary;
- holdings/positions;
- realized/unrealized P&L;
- equity curve;
- drawdown;
- strategy performance;
- scanner/research performance;
- daily/weekly/monthly performance summaries;
- risk utilization;
- broker/market-data/news/research health;
- alerts for failures, stale data, risk events, emergency stop, reconciliation differences and deployment health;
- notification persistence/read status;
- audit search/export with bounded pagination.

AI-generated commentary, if added, remains optional/read-only and must be clearly provenance-labelled.

### Exit gate

Operators can determine system/account/research health and reconcile every trading-impacting event from APIs without direct database access.

---

## Phase 9 — Complete dual frontend experiences

**Status: IN PROGRESS**

### Existing baseline

Experience selector, shared shell, responsive/mobile foundations and first Clear Invest dashboard slice exist. Pro Terminal remains the denser existing experience.

### Mandatory screens/flows for BOTH Pro Terminal and Clear Invest

1. Overview/dashboard.
2. Market discovery / instruments.
3. Market data/history.
4. Scanner/research candidates.
5. Candidate detail/explanations.
6. Backtest validation.
7. Scenario/regime analysis.
8. Signals.
9. Simulated order review.
10. Approval queue/policy state.
11. PAPER orders/history.
12. Positions/holdings.
13. Portfolio/P&L/performance.
14. Risk limits, utilization and rejection history.
15. News/research context.
16. Notifications/operational status.
17. Settings / experience selection / account/runtime mode.
18. Audit/history views required for operator support.

### UX requirements

- shared business APIs/state; no duplicated trading logic between designs;
- distinctly composed Pro Terminal and Clear Invest experiences;
- no neumorphism;
- responsive 320px through desktop widths;
- accessible keyboard/focus/labels/contrast;
- loading/empty/error/stale states;
- safe PAPER/LIVE visual distinction;
- dangerous actions require explicit confirmation;
- experience switch must not lose current route/auth/forms/order state;
- persist local preference; account-level sync when user-profile persistence is introduced;
- visual regression screenshots for both experiences.

### Exit gate

Every mandatory product workflow is feature-equivalent in both designs, responsive and accessibility/E2E tested.

---

## Phase 10 — Authentication, authorization and security hardening

**Status: PARTIAL**

### Mandatory scope

- replace development/operator-key assumptions with production authentication architecture;
- define roles (at minimum operator/admin/read-only where appropriate);
- server-side authorization on every privileged API;
- credential/session expiry and revocation;
- CSRF/CORS strategy appropriate to deployment model;
- request validation and bounded pagination everywhere;
- secret redaction in logs/errors;
- rate limiting/abuse controls for sensitive APIs;
- dependency/security scanning;
- database least privilege;
- immutable security/audit event coverage;
- threat model for research/PAPER/LIVE flows;
- SSRF/XML/parser protections maintained for news;
- no broker/API secrets in browser bundles;
- secure headers/TLS/cookie policies for production;
- backup/restore authorization and operational access controls.

### Exit gate

Security review finds no known critical/high launch blockers, privileged APIs are authenticated/authorized, and secrets are isolated from the frontend/logs/source.

---

## Phase 11 — Mobile/PWA and platform packaging

**Status: PARTIAL**

### Existing baseline

Responsive mobile UI, safe-area work, standalone manifest/installability metadata and CI validation exist.

### Mandatory V1 scope

- production raster icons/splash assets;
- installability/manifest verification on supported browsers;
- Android Chrome/PWA validation;
- iOS Safari/Add-to-Home-Screen validation;
- offline behavior limited to safe shell/static UX — **never queue or replay trading actions offline**;
- reconnect/stale-state handling;
- physical-device responsive testing;
- accessibility/touch target validation.

### Packaging decision gate

Current repository is web/PWA-first. Native iOS/Android applications are **not assumed to exist**.

If Android Play distribution is required for V1, add a TWA packaging subtask with Digital Asset Links and store requirements. If iOS native packaging is required, it requires a separate explicit technical choice; otherwise iOS V1 is responsive PWA/web.

### Exit gate

Supported mobile delivery surfaces behave safely with no hidden browser/offline execution semantics and pass device validation.

---

## Phase 12 — AWS infrastructure and production deployment

**Status: EXTERNAL WAIT for apply; implementation work may continue**

### Current state

Terraform, Docker and GitHub deployment foundations already exist. AWS account activation/details are pending.

### Rule

**AWS availability is not a blocker for Phases 1–11, 13 or most of 14.** Continue implementation using local/Testcontainers/CI environments.

### Mandatory tasks before apply

- select exactly one deployment topology for V1; do not provision both stacks;
- current AWS pricing/region/service availability review;
- final Terraform plan review;
- environment naming/tagging;
- remote Terraform state/locking decision;
- GitHub OIDC least-privilege review;
- Secrets Manager/Parameter Store design;
- production PostgreSQL persistence/backup/restore choice;
- HTTPS/domain/DNS/certificate plan;
- log retention and CloudWatch alarms;
- AWS Budget/billing alerts;
- rollback and disaster-recovery runbook.

### When AWS account becomes active

1. confirm account/region/domain details;
2. configure OIDC and required repository/environment secrets;
3. run `terraform plan` only;
4. review cost/security plan;
5. explicitly approve apply;
6. provision non-production/staging first;
7. deploy immutable images;
8. verify DB migration, TLS, health, backups, alarms and rollback;
9. only then provision/promote production.

### Exit gate

Production environment is reproducibly provisioned, encrypted, TLS-protected, monitored, backed up, budgeted and rollback-tested.

---

## Phase 13 — Observability, reliability and production hardening

**Status: PARTIAL**

### Mandatory scope

- structured logging with correlation IDs;
- metrics for API latency/errors, DB pool, market-data ingestion, scanner duration, simulation duration, risk rejects, orders/fills, reconciliation drift and provider failures;
- dashboards and alarms;
- health/readiness endpoints for dependencies;
- load tests for read APIs and scanner/research workloads;
- long-running simulation bounds/cancellation/timeouts as needed;
- restart/recovery tests;
- DB connection exhaustion tests;
- provider timeout/rate-limit/outage tests;
- graceful degradation for optional news/AI;
- chaos/failure drills for critical PAPER components;
- backup restore drill;
- release rollback drill.

### Exit gate

The system fails closed for execution-impacting dependencies, degrades safely for optional dependencies and can be diagnosed/recovered using operational telemetry.

---

## Phase 14 — Production PAPER launch / V1 go-live

**Status: NOT STARTED**

This is the first product production launch milestone and does **not** require real-money broker order submission.

### Mandatory launch checklist

- Phases 0–13 mandatory gates complete except explicitly deferred LIVE-only items;
- staging soak test completed;
- production migration rehearsal completed;
- seed/import required instrument data;
- market-data small-range provider verification;
- scanner/research validation in production-like environment;
- PAPER order/risk/account reconciliation test;
- dual UI smoke/E2E tests;
- mobile/PWA device smoke tests;
- authentication/authorization/security validation;
- alarms/budget/backups/restore/rollback verified;
- support/admin runbook;
- known limitations documented;
- release tag/version created;
- production deployment approved.

### Definition of V1 production live

Users/operators can securely use market data, research/scanners, backtests, scenario analysis, risk controls and PAPER trading through both supported frontend experiences on the production environment, with audited/reconcilable state and operational monitoring.

### Exit gate

V1 production PAPER/research release is deployed and passes post-deploy verification with no critical launch defect.

---

## Phase 15 — Optional Angel One real-money LIVE pilot

**Status: NOT STARTED — separate explicit authorization required**

This phase is finite but is deliberately separated from V1 PAPER launch.

### 15.1 Regulatory/broker/API review

Before any real order:

- verify current Angel One SmartAPI order/session/rate-limit capabilities;
- verify current SEBI/exchange/broker requirements applicable to the intended usage;
- define allowed order types/products/sessions;
- record compliance/operational decision and restrictions.

### 15.2 Broker gateway

- `BrokerOrderGateway` abstraction;
- mock implementation first;
- Angel One read-only connectivity;
- auth/session lifecycle;
- funds, holdings, positions and orderbook reconciliation;
- contract tests.

### 15.3 Durable LIVE order workflow

- persisted immutable order intent;
- idempotency/client order ID;
- request hash;
- submission/status lifecycle;
- timeout/unknown-outcome handling;
- retry without duplicate order;
- cancel/reject/partial fill;
- process-restart recovery;
- broker reconciliation;
- immutable correlation/audit trail.

### 15.4 LIVE pre-trade gate

Immediately before broker submission re-check:

- execution mode and explicit activation;
- operator approval/policy;
- emergency stop;
- market session;
- fresh quote;
- instrument/exchange validity;
- funds/holdings;
- per-order/per-trade limits;
- symbol/portfolio exposure;
- daily loss/trade limits;
- duplicate/pending exposure;
- broker connectivity/health.

### 15.5 LIVE UI/operations

- unmistakable LIVE banner/mode;
- approval queue;
- order state and reconciliation;
- broker health;
- emergency stop;
- alerts;
- LIVE audit timeline;
- no ambiguous PAPER/LIVE controls.

### 15.6 Controlled pilot

1. extended PAPER shadow run;
2. kill-switch drill;
3. explicit go-live authorization;
4. very small configured notional/trade-count limits;
5. MANUAL approval initially;
6. limited pilot orders;
7. same-day broker/platform reconciliation;
8. incident/rollback review;
9. staged expansion only after operator sign-off.

### Exit gate

A separately approved controlled LIVE pilot has executed within configured limits with correct reconciliation, observability, risk enforcement and no unresolved critical defects.

`AUTO` real-money operation is **not** implied by completing the pilot and requires its own explicit approval/policy configuration.

---

# 5. Phase dependency map

Primary sequence:

```text
0 Foundation
  -> 1 Market Data
  -> 2 Strategies/Scanner
  -> 3 Backtesting
  -> 4 Scenario/Regime Research
  -> 5 Risk Completion
  -> 6 PAPER Accounting
  -> 7 Approval Policies
  -> 8 Analytics/Operations
  -> 9 Dual Frontend Completion
  -> 10 Security
  -> 11 Mobile/PWA
  -> 13 Reliability/Hardening
  -> 14 Production PAPER Launch
  -> 15 Optional LIVE Pilot
```

Phase 12 (AWS) runs in parallel when account activation is available and must be complete before Phase 14 production launch.

Frontend work in Phase 9 may proceed in parallel once the relevant backend contract is stable.

---

# 6. Immediate execution order from the current repository state

The next implementation sequence should be:

1. **Phase 4:** finish regime-aware simulation and persist simulation provenance/results.
2. **Phase 4:** connect scanner candidate -> backtest/scenario research workflow APIs.
3. **Phase 5:** complete missing deterministic risk rules and policy snapshots.
4. **Phase 6:** complete portfolio accounting/reconciliation/P&L semantics.
5. **Phase 7:** implement MANUAL/AUTO/HYBRID approval policy engine for PAPER first.
6. **Phase 8:** expose complete portfolio/performance/operations APIs.
7. **Phase 9:** finish dual-design migration screen-by-screen against stable backend contracts.
8. **Phase 10/11/13:** security, mobile and reliability hardening in parallel.
9. **Phase 12:** when AWS activates, provision staging and production without blocking code progress.
10. **Phase 14:** production PAPER/research go-live.
11. **Phase 15:** only after explicit approval, execute broker/live implementation and controlled pilot.

---

# 7. Definition of Done for every implementation PR

A feature PR is not complete merely because code exists.

Mandatory where applicable:

- implementation matches a work item in this plan;
- backend unit tests;
- PostgreSQL/Testcontainers integration tests for persistence/transaction behavior;
- deterministic/replay tests for trading/research logic;
- security/boundary tests for protected inputs;
- frontend production build;
- UI states for loading/empty/error/stale data;
- both design experiences updated when user-facing scope applies;
- mobile/responsive validation when UI applies;
- Docker build;
- Terraform validation when infrastructure changes;
- no secret exposure;
- no LIVE-mode safety regression;
- migration is forward-only;
- documentation/source-of-truth status updated;
- full required CI green before merge.

---

# 8. Explicit V1 exclusions / later scope

Unless explicitly promoted, the following do not block Phase 14 V1 PAPER production launch:

- F&O/options/futures;
- scalping/high-frequency trading;
- multiple live brokers;
- autonomous AI-driven order decisions;
- social/copy trading;
- native Flutter/React-Native apps;
- offline order submission/replay;
- multi-tenant public SaaS billing/subscriptions;
- automatic real-money trading without separately approved LIVE policy.

---

# 9. Source-of-truth change control

This plan is intentionally finite.

A new numbered phase should **not** be added for normal implementation details. New work belongs under an existing phase.

Add or redefine a phase only when one of these changes:

- product market/scope materially changes;
- a new regulated asset class is added;
- a new execution model/broker fundamentally changes architecture;
- a new platform (for example native mobile) is formally made mandatory;
- production compliance/security requirements require a distinct release gate.

Any such change must explicitly state:

- why existing phases cannot contain the work;
- launch impact;
- dependency impact;
- whether V1 scope has changed.

---

# 10. Completion dashboard

| Phase | Name | Current status | Blocks V1 PAPER launch? | Blocks LIVE pilot? |
|---|---|---|---|---|
| 0 | Architecture / CI foundation | DONE | Yes — satisfied | Yes — satisfied |
| 1 | Instrument + market data readiness | PARTIAL | Yes | Yes |
| 2 | Strategy + scanner suite | PARTIAL | Yes | Yes |
| 3 | Backtesting + validation | PARTIAL | Yes | Yes |
| 4 | Scenario/regime research | IN PROGRESS | Yes | Yes |
| 5 | Risk engine completion | PARTIAL | Yes | Yes |
| 6 | PAPER accounting/portfolio | PARTIAL | Yes | Yes |
| 7 | Approval policy/operator workflow | NOT STARTED | Yes | Yes |
| 8 | Analytics/alerts/operations backend | PARTIAL | Yes | Yes |
| 9 | Complete dual frontend | IN PROGRESS | Yes | Yes |
| 10 | Auth/security hardening | PARTIAL | Yes | Yes |
| 11 | Mobile/PWA validation | PARTIAL | Yes | Yes |
| 12 | AWS production deployment | EXTERNAL WAIT | Yes | Yes |
| 13 | Reliability/observability hardening | PARTIAL | Yes | Yes |
| 14 | Production PAPER V1 launch | NOT STARTED | Final V1 gate | Prerequisite |
| 15 | Optional Angel One LIVE pilot | NOT STARTED | No | Final LIVE gate |

---

## Final rule

Implementation does **not** continue indefinitely. The mandatory roadmap ends at Phase 14 for the production PAPER/research product and Phase 15 for the separately authorized real-money pilot. Once the applicable exit gates are complete, the product is launched; subsequent feature ideas belong to a new post-V1 roadmap rather than silently extending this one.


---

# LIVE-FIRST SMARTAPI PRODUCT ALIGNMENT — 2026-10-28

**Product intent clarification:** AiTradingSystem is a production LIVE trading platform with PAPER/shadow mode for validation and safety. Phase 15 is the controlled *activation* of real-money execution, not the first phase in which LIVE capabilities are architected. Design, database models, adapters, UI and tests for LIVE must be delivered incrementally across earlier phases. Do not enable order submission merely because the adapter exists.

## Official sources and version verification
- SmartAPI reference: https://smartapi.angelone.in/docs/Edis/
- Official Java SDK routes: https://github.com/angel-one/smartapi-java/blob/main/src/main/java/com/angelbroking/smartapi/Routes.java
- Official Python SDK routes: https://github.com/angel-one/smartapi-python/blob/main/SmartApi/smartConnect.py
- Angel One April 2026 algo trading requirements: https://www.angelone.in/news/market-updates/what-s-changing-in-angel-one-s-smartapi-access-from-april-1-2026

**Research finding:** The existing adapter implements loginByPassword, getLtpData and getCandleData only. The SDK documents additional endpoints; documentation presence does not establish permission or runtime availability for our account. Check path differences between SDKs, particularly GTT service routes, against the current official reference before implementing.

## Required capability inventory and delivery order

| Family | Required operations | Design integration | Target |
|---|---|---|---|
| Session | login, token refresh, profile, logout | centralized token lifecycle, rotation, session-expiry recovery | P1/P10 |
| Instrument | official instrument master, searchScrip, cautionary-scrip check, NSE/BSE intraday eligibility | canonical instrument ID, daily reconciliation, eligibility checks | P1/P5 |
| Quotes | market/v1/quote LTP/OHLC/FULL; existing getLtpData | normalized quote, depth, volume, circuit bands, freshness | P1/P2/P5 |
| Streaming | WebSocket V2 market feed | central subscriptions, binary parser, heartbeat, reconnect, gap recovery | P1/P8/P13 |
| Historical | getCandleData; getOIData if derivatives enabled | bounded historical ingestion, provenance and replay | P1/P3 |
| Cost/margin | estimateCharges, margin/v1/batch | versioned cost model and LIVE buying-power checks | P3/P5/P6/P15 |
| Orders | placeOrder, modifyOrder, cancelOrder, individual details | durable LIVE intent, idempotency, UNKNOWN recovery | P6/P7/P15 |
| Execution events | order-status WebSocket / postback, getOrderBook, getTradeBook | event dedupe, monotonic state machine, broker reconciliation | P6/P8/P13/P15 |
| Broker accounts | getRMS, getHolding, getAllHolding, getPosition, convertPosition | account reconciliation, margin/exposure checks | P6/P8/P15 |
| Conditional orders | GTT create/modify/cancel/details/list | feature-flagged, broker capability and product validation | conditional P15 |
| Delivery authorization | EDIS verifyDis, generateTPIN, getTranStatus | required only for supported delivery-sale workflows | conditional P15 |
| Research enrichments | gainersLosers, NSE/BSE intraday, cautionary instruments | research signals; never bypass deterministic risk | P2 |
| Derivatives enrichments | optionGreek, putCallRatio, OIBuildup, historical OI | only if derivatives explicitly added to scope and risk engine | post-V1 unless separately approved |

## LIVE-grade engineering invariants

1. **Broker gateway built early:** define \`BrokerGateway\`, \`MarketDataGateway\`, \`BrokerAccountGateway\`, \`BrokerExecutionEventGateway\` interfaces; use a strict PAPER adapter and a LIVE Angel One adapter. Never route orders based on a UI-supplied mode flag.
2. **Order intent and unknown outcomes:** write a durable idempotent intent before network submission; never automatically resubmit a timed-out/unknown order without querying broker order details/order book and reconciling.
3. **Risk checks before submission:** validate exchange session, eligible instrument, quote freshness, lot/tick, account margin/cash, exposure, quantity, cost, limits and kill switch. Broker RMS/margin checks are necessary for LIVE but not sufficient substitutes for our own risk policy.
4. **Execution event model:** reconcile place response, order-status stream/postback, order book, trade book and positions. Deduplicate, handle out-of-order events, partial fills, rejects and cancel/modify races.
5. **Rate limits and stream quotas:** centralized budgets by endpoint and account; provider docs specify cumulative order place/modify/cancel rate of 9 requests/sec, 500/min, 1000/hour; WebSocket up to 3 concurrent connections/client and 1000 token-mode subscriptions/session. Verify current limits before go-live.
6. **2026 compliance gate:** registered primary static egress IP for API order placement, applicable exchange/broker algo IDs, exchange-segment order rate controls, deployment/hosting eligibility, and permitted order varieties. Angel One states market and IOC orders are prohibited for algorithmic trading under its April 2026 changes; validate applicability and exact approved order types before enabling LIVE.
7. **Security:** backend-only broker secrets, separate PAPER/LIVE account identifiers, encrypted token storage, scoped permissions, privileged LIVE enablement, audited emergency stop and reconciliation-first restart.
8. **Observability:** quote freshness, websocket gap/reconnect, rate-limit budget, broker order latency, UNKNOWN order count, fills unmatched to intents, broker-versus-local position/cash divergence; fail closed for new entries on uncertain state.
9. **Data rights:** validate Angel One market-data storage, display, sharing and commercial licensing restrictions before any multi-user commercialization.
10. **Acceptance:** official SDK contract tests, synthetic/sandbox provider tests, deterministic order state-machine tests, failure injection, manual small-limit pilot, signed-off compliance and deployment review. Connection test and credential provisioning can be deferred without deferring architecture.

## Roadmap interpretation

Phases 0–14 now deliver **LIVE-capable architecture and operational readiness**, with PAPER/shadow execution proving correctness. Phase 15 activates real-money broker submission only after external dependencies, security, regulatory and broker checks pass. All phase implementation PRs must state how their data/contracts work for both PAPER and LIVE. This section clarifies scope; it does not assert existing LIVE code or permission.
