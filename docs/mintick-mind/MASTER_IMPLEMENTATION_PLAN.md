# Mintick Mind — Single-User-First, Multi-User-Ready Master Implementation Blueprint

**Plan ID:** MIND-MASTER-2026-11-09  
**Product:** MintTick | **Engine:** Mintick Mind  
**Status:** Approved direction / implementation plan, **not** evidence of production readiness  
**Repository audited:** operationReact/AiTradingSystem, dev @ `6a72b04b059e276996f41152feb03fdc49abd109` (2026-11-09 snapshot; refresh HEAD before implementation)  
**Document owner:** project owner; implementation assignees unassigned. No estimated dates or cost figures are commitments.  
**Notion relationship:** companion to existing AiTradingSystem V4 multi-model plan (https://app.notion.com/p/40d36a828cb6812594c0e657fe5db1b3). Do not override its completed V3/V4 safety contracts.

> **Non-negotiable architecture:** Mintick Mind is a **user-independent market-intelligence and research engine**. Market data, general features, predictions and strategies belong to the shared engine. User/account data are consumed only by a **separate personalization, deterministic risk and broker-execution boundary**. Market-outcome learning is separate from user trade/execution learning. V1 serves **one owner**; isolation and identifiers are designed to extend to multiple users without needing multiple copies of the core engine.

## Documentation navigation

1. [This master plan](MASTER_IMPLEMENTATION_PLAN.md) — decisions, architecture, full system scope, phase dependency graph, critical acceptance gates.
2. [42 screen contracts](SCREEN_CONTRACTS_42.md) — every route, section, widget, source DTO, interaction, API, empty/error/stale state, authorization and test.
3. [18 models + 232 strategy contracts](MODELS_AND_STRATEGIES.md) — registry, activation thresholds, model input/output contracts and **every named strategy ID**.
4. [Canonical data architecture](DATA_ARCHITECTURE_SCHEMA.md) — entity dictionary, event/time semantics, migrations, provider boundaries, storage, retention, quality, scaling.
5. [Phase-by-phase delivery runbook](PHASE_DELIVERY_RUNBOOK.md) — phase-specific tasks, sequence, ownership boundaries, integration tests, release/rollback evidence.
6. [Repository reuse and gap audit](EXISTING_REUSE_AND_GAPS.md) — actual reusable code, migrations, workflows, dormant features and non-verifications.
7. [Traceability and nonfunctional acceptance](TRACEABILITY_AND_RELEASE_GATES.md) — screen × contract × phase, model eligibility, performance, safety, cost budgets, test matrix.

## 0. Agreed product decisions and explicit non-goals

### Approved now
- Indian exchanges and Indian-market instruments; support cash equities and indices first, with F&O **research-only** until data, licensing, broker/product eligibility, margin and risk controls are verified.
- Preserve both Paper and Live modes as product concepts; do **not** make Paper a brand identity. LIVE execution is separately blocked by policy until explicit authorization, secure credentials and certified operational gates.
- Preserve near-black/mint-green premium visual direction, light + dark themes, accessible contrast, smooth motion and clear stale/demo status.
- One owner's actual trading in V1, with future multi-user readiness via isolation-ready APIs, ownership columns and broker-account authorization. No payment, subscriptions, tenant admin console or per-user microservices in V1.
- 42 Flutter routes, 18 model **records**, 232 strategy **templates in 20 families** are a design inventory. They are NOT 42 live screens, 18 proven models or 232 executed live strategies. Existing synthetic scores and returns must never be presented as verified.
- Java/Spring Boot retains authoritative access-control, data-validation, risk, order-approval and execution gates; Python is an optional, bounded, auditable research/inference worker. Flutter is a client, never an execution authority.
- Prefer validated, versioned, causal features and model evidence to model count. Cost and latency are first-class promotion criteria.
- Avoid redesign/rewrite of working V3/V4 subsystems; migrate in additive, reversible vertical slices.
- No AWS infrastructure creation, secret rotation, LIVE authorization, broker order submission or automatic model promotion is authorized by this documentation change.

### Explicitly deferred
- Public multi-user onboarding, billing, subscriptions, market-data redistribution and multi-tenant hosting; assess licence/regulatory needs before expansion.
- Intraday tick-by-tick market-wide retention, all-market always-on scanning, HFT/order-book strategies, expensive third-party datasets and 24/7 GPU training.
- True automation of unverified strategies, RL trading, option writing, external LLM-directed order placement and autonomous capital approval.
- Arbitrary model churn and per-screen unbounded external API requests.

## 1. What exists on dev versus what must be built

| Layer | Existing source-grounded evidence | Gap / acceptance required |
| --- | --- | --- |
| Flutter | 42 named native routes and synthetic fixtures in `apps/trading_client/lib/src/features/mintick_mind`; interactive UI shell | Replace fixtures via typed repositories; per-widget data freshness, loading/empty/error; visual parity/goldens + Android device testing |
| Demo backend | `MindDemoController` optional Spring profile `mind-demo`, ten read-only authenticated synthetic routes | No production Mind screen API aggregator or Flutter HTTP consumption; keep demo explicitly isolated |
| Instruments | Versioned catalogue, research resolver, public sync; migrations V4-V6, V48 | Cross-provider canonical identity, corporate actions, NSE/BSE aliases, daily expiry handling and quality SLAs |
| Market data | Broker-neutral `MarketDataProvider`, historical candles, backfill, gap detection, market last tick, reconciliation, official-source archive | Production-connected owner feed verified end-to-end, entitlements, missing history policy, WebSocket lifetime, quote buffering and backpressure |
| Research | Backtesting, walk-forward, scenario research, scans, paper, shadow and V4 causal projection/receipt ledger components | Evidence-backed 232-template eligibility; promotion gates; model training/serving coverage; realistic net-of-cost scoring |
| Governance | Registry, emergency control, bounded inference, prospective evidence and causal input fingerprints; paper/shadow safety | Unified Mind runtime orchestration, authoritative control-plane snapshots, all 18 model state evidence and live-trading separation |
| Trading | Cognito/auth, Angel One owner credentials through AWS SSM, Upstox connector scaffolding, rooms and watchlists | Confirm broker authorization end-to-end and independent account custody; owner market-data session must not become user execution authority |
| Operations | Multiple Flutter/Java/worker CI gates, EC2/EBS/Postgres budget IaC and optional dormant ECS worker IaC | Identify **actual deployed infrastructure** (IaC does not prove it exists), observe CPU/memory/storage/egress, and define go/no-go |

## 2. System decomposition and deployment boundaries

```text
PUBLIC / LICENSED INDIA MARKET FEEDS + OFFICIAL DOCUMENTS
  |-- Angel One, optionally Upstox: live quotes, candles, instruments
  |-- NSE/BSE official reports and corporate filing feeds, RBI macro
  |-- news providers with provenance and usage rights
  v
[INGESTION/VERIFICATION CONTEXT — independent of users]
 provider adapters -> entitlement/rate guard -> canonical instrument
 -> timestamp/sequence validation -> quality ledger -> raw immutable references
 -> PostgreSQL recent bars + S3/Parquet archival + bounded hot cache
  v
[FEATURE & RESEARCH CONTEXT — independent of users]
 indicator DAG (incremental) -> regime/breadth/sector/fundamental features
 -> versioned feature snapshots -> strategy scanners -> model inference policy
 -> 18-model registry (not all active) -> ensemble/critique -> evidence ledger
 -> calibrated expected outcomes, uncertainty, WAIT/ABSTAIN, no order authority
  |                                             |
  +--- > [MARKET OUTCOME LABELER & LEARNING] ---+
  v
[PERSONAL DECISION CONTEXT — account scoped]
 account/room policy + holdings + available funds + portfolio exposures
 -> personalized idea ranking -> deterministic Java risk/capital/eligibility
 -> explicit mode and order authorization -> broker order idempotency/receipt
 -> fills, reconciliation, fees, P&L, trade journal
  |
  +--- > [EXECUTION FEEDBACK] kept distinct from market prediction learning
  v
[API/CLIENT]
 authenticated read models + paginated detail + bounded push updates
 -> Flutter 42 screens (both themes; stale/demo/disabled status always visible)

Boundary rule: Python or LLM can add evidence, NEVER mutate funds, submit broker
orders, override risk/emergency stop, promote to LIVE, or fetch raw secrets.
```

### Engine services (logical modules before microservices)
1. **Reference & Calendar** — canonical instruments, aliases, exchange holidays, session cutoffs, expiries, corporate actions, index membership.
2. **Market Feed** — provider client, WebSocket session ownership, REST fallback, heartbeats, quote quality, channel fan-out, instruments of interest.
3. **Time-Series Store** — candles by timeframe, backfill cursors, completeness/adjustments, immutable source metadata.
4. **Official Document Ingestion** — filings, announcements, financial facts, institutional reports, macro and news, with licensing and publish/available timestamps.
5. **Feature DAG** — technical/sector/regime/fundamental/derivatives features with causal availability cutoffs, fingerprints and corrections.
6. **Strategy Registry/Scanner** — template definitions, parameter schemas, dependency readiness, instrument/horizon eligibility, research vs paper vs shadow states.
7. **Model Registry/Runner** — champion/challenger, artefacts, schema enforcement, bounded CPU/GPU, timeout, cache and absent-model fallback.
8. **Decision Engine** — structured model votes, evidence lineage, disagreement, uncertainty, expected net benefit, abstention, before-risk research decision.
9. **Portfolio/Capital Authority** — account isolation, room allocations, deterministic limits, risk decisions, order intents, broker sessions, reconciled fills.
10. **Experiment/Outcome Platform** — point-in-time datasets, purged walk-forward, baseline, paper/shadow, labels, calibration, drift and Culprit Catcher.
11. **API/Projection Layer** — typed DTOs, response status, authenticated identity, last-known-good, pagination, streaming deltas and read-only explanation.
12. **Operations/Governance** — schedules, backpressure, reconciliations, kill switches, audit, alerting, secrets, release gates and per-source cost ledger.

### Runtime layout (cost-first)
- Begin as modular Spring Boot Java with Postgres and bounded in-process latest-value cache; isolated Python worker only for verified research/inference workloads.
- Use a durable database outbox/job table for retry-safe async jobs before introducing managed Kafka/SQS/Redis. Expand only on measured need; idempotency keys and resume cursors required from day one.
- Keep live quote fan-out and expensive model work off HTTP request threads; precompute shared results, then serve fast read models.
- Retain immutable bulk archives in S3/Parquet if licensed and needed, not every tick forever; daily backups and restore drills are mandatory.
- Use EC2/EBS if actual deployment confirms budget, operational/backup safety and adequate headroom. Existing alternative ECS/Fargate IaC is **not** a reason to run a second always-on stack.
- Single-user V1 does not excuse missing tenant/user/account scopes on private tables. It also does not justify duplicated user-private market feeds.

## 3. Data inventory and refresh policy

| Domain ID | Data contract / fields | Typical schedule | Source caveat |
| --- | --- | --- | --- |
| D01 | instrument_id, venue, segment, ISIN, symbol/alias, broker token, lot/tick/expiry, validity | daily/on expiry | token changes and delistings |
| D02 | exchange timestamp, receive timestamp, source, seq, LTP, bid/ask, volume, last traded qty, quality, market phase | event-based for subscribed symbols | no imaginary streaming |
| D03 | raw/adjusted OHLCV, interval, bar end, volume, currency, correction version, backfill state | on closed bar + audit | corporate actions and holes |
| D04 | NIFTY indices, breadth, sector ranks, VIX, market phase, constituents as-of | live/daily | constituents are point-in-time |
| D05 | standardized financial statements, periods, filing/publication/available_at, restatement, currency | filings/events | point-in-time, no future leakage |
| D06 | dividends/splits/bonus/rights/buybacks/results/pledges, announcement and ex/record date | event-driven | original primary-source evidence |
| D07 | FII/DII aggregate flows, provisional/final labels, participant stats, delivery volume | official daily report | no stock-specific inference from totals |
| D08 | RBI rates, FX, yields, crude, inflation/calendar, release/revision times | periodic | aligned release vs effective dates |
| D09 | licensed news text, publisher URL, discovered_at, published_at, symbols, duplicate cluster, AI evidence | event-driven | license and source confidence |
| D10 | option/futures contract, expiry, strike, call/put, bid/ask, OI, IV, Greeks, margin, settlement | only active derivatives module | cost, licence and latency |
| D11 | feature_id/version, lookback/horizon, instrument/time, available_at, value, source hashes, quality | closed bars/events | shared computation |
| D12 | model_version, training cutoff, input schema, artifact digest, calibration, prediction, provenance | selective research/inference | not all 18 on each symbol |
| D13 | strategy rule/parameter version, eligible universe, features, signal, abstention and transaction-cost model | horizon-appropriate | 232 templates not deployed algorithms |
| D14 | trading_room, account, cash, positions, broker capabilities, risk policy, margin, order/fill ledger | broker event/poll | private user-bound |
| D15 | counterfactual, market outcome label, trade outcome, leakage audit, fees, incidents and root-cause evidence | horizon maturity/close | distinguish market vs execution feedback |
| D16 | source entitlement, ingestion run, incident, freshness SLA, job state, billing metrics and audit | continuously | controls every downstream layer |

### Selective universe/compute plan
- Proposal only: 500 liquid cash symbols for broad daily research; 200 rotating intraday candidates with 5-min closed-bar features; 50 owner active/watch symbols with 1-min/live quote subscription.
- Dynamically include any held position and subscribed critical index. Deduplicate token-mode subscriptions and reconcile provider entitlement/rate limitations before going live.
- One candle/feature/prediction dataset can serve many screens and eligible users. Cache key must include instrument, data cutoff, feature/model/policy version and entitlement context when necessary.
- **No model** should infer on an unfinished candle as if it were final unless that is explicitly an intrabar strategy with its own tested contract.

## 4. User-independent learning versus user feedback

- **Global market intelligence:** observed market outcomes after horizon maturity, purged/unbiased feature/label data, regime-conditional research. Never require user portfolio, broker or P&L to score a market opportunity.
- **Personalization:** account/room allocation, holdings, loss limits, preferred horizons, planned orders, broker permissions. Output is an account-scoped **suggestion**, not a mutation of global predictions.
- **Trade execution feedback:** order receipt, partial fills, broker latency, spread/slippage, execution error and net P&L. Train separate execution/slippage models only after enough validated samples.
- **Feedback governance:** user outcomes never automatically change shared champion weights. They enter a quarantined evidence store, are quality checked, split from training data appropriately and need explicit promotion.
- **Multi-user later:** tenant/user/account IDs on every private record; ownership authorization on every request, server-resolved subject; no arbitrary client-supplied owner; cross-tenant noninterference tests; entitlements checked before redistributing market data.

## 5. Model and strategy governance

- Model states: DISCOVERED -> LICENCE_REVIEWED -> RESEARCH_ENABLED -> BACKTEST_PASSED -> PAPER_VALIDATED -> SHADOW_VALIDATED -> GOVERNANCE_CANDIDATE -> CHAMPION. Separate DISABLED, QUARANTINED and ROLLED_BACK; **no implicit LIVE transition**.
- Registry lists the 18 named models as records, many research-only/adapter/planned. Qualified primary tabular benchmark first; challenger earned by net after-cost out-of-sample benefit, latency and incremental diversity.
- Every strategy must have machine-readable input contract, parameter schema, signal/abstention semantics, cost assumptions, market phase, eligibility and test fixtures. **Catalogue is not runtime authorization.**
- Agents are bounded evidence evaluators (Bull, Bear, Fundamental, Flow, Critic). Council output must record vote inputs and challenges; no agent may assert verified evidence without valid source references.
- Use proper model calibration (Brier/log-loss/ECE/reliability bins), walk-forward folds, embargo where appropriate, survivorship protection, sample sufficiency and regime/sector breakdown.
- Always maintain a deterministic research baseline and well-defined fallback WAIT/ABSTAIN.
- Record failures, missed trades, broker rejects and model disagreement even when no order is placed. A decision missing required evidence is ABSTAIN, not BUY by default.

## 6. API and response contract

Each production endpoint is authenticated and versioned under `/api/mind/v1/`; avoid colliding with existing `/api/research/` and `/api/mind/demo/v1/`. Java facade adapts existing services rather than duplicating repositories.

Response envelope:
```json
{
  "contractVersion": "mind-read.v1",
  "kind": "MARKET|RESEARCH|ACCOUNT|SYSTEM",
  "asOf": "ISO-8601 UTC exchange/feature cutoff",
  "generatedAt": "ISO-8601 UTC",
  "sourceStatus": "VERIFIED|PARTIAL|STALE|UNAVAILABLE|SYNTHETIC",
  "entitlement": "AUTHORIZED|NOT_AUTHORIZED|NOT_REQUIRED",
  "modelOrPolicyVersion": "optional immutable identifier",
  "executionEnabled": false,
  "data": {},
  "warnings": [],
  "traceId": "request trace identifier"
}
```
- Use **HTTP 4xx/5xx** for genuine errors, not 200 with fabricated prices. Return partial data + reason when degraded; display last-updated and stale source on every affected UI widget.
- Read endpoints GET, batch where useful; writes require CSRF/auth policy where applicable, idempotency token, optimistic version and audit. Account-owned resources use server-derived identity.
- Existing `/api/mind/demo/v1/*` stays opt-in and labeled SYNTHETIC; never mix data silently in production projections.
- WebSocket/SSE subscriptions authenticate, authorize instruments, are bounded per client and fall back to snapshot/read mode on disconnect.
- Never send broker raw credentials, TOTP seed, server API keys or proprietary source documents to Flutter/LLM.

## 7. UI and interaction system shared by all 42 screens

- Shell: app return navigation, workspace route, title/description, breadcrumbs, accessible back behavior, side navigation, responsive mobile/tablet/desktop, theme, request cancel, status/freshness chip.
- Reusable components: MarketStatusBar, InstrumentSearch, QuoteCard, Sparkline, Candlestick, DepthView, MarketBreadthPanel, RegimeBadge, FeatureTrace, EvidenceChip, AgentVoteCard, ConfidenceGauge, ReliabilityPlot, ModelHealthCard, StrategyTile, DecisionTimeline, ApprovalGate, OrderStatus, RiskBudgetCard, IncidentTimeline, EmptyState, StaleDataState, SourceLegend, ErrorRetry, AuditExport.
- States for **every** data widget: initial/loading/refreshing/success/partial/empty/stale/unauthorized/disabled/error, including skeleton loading, offline cached snapshot with timestamp, retry, density-independent text sizing and accessibility contrast.
- All mock, hypothetical, stale, after-market and unverified data visibly differentiated; no green/amber/color-only trade instruction. Positive/negative color must not override risk veto.
- Every screen contract in [SCREEN_CONTRACTS_42.md](SCREEN_CONTRACTS_42.md) lists owner module, sections, inputs, endpoint, actions, fallback and tests; design gallery/preview intentionally remain static or visual-only.
- UI API clients generated/typed from versioned OpenAPI schemas; contract tests between Spring and Flutter, and golden tests per theme and screen geometry. Samsung S23 Ultra reference testing plus at least one smaller Android layout.

## 8. Operational and economic constraints

1. Define observed baselines and goals, not unsupported claims: cached read p95 <300 ms target; watch screen end-to-end refresh target 1-2 sec **after backend receives quote**; closed-bar feature processing target <2 sec; broker execution latency measured separately.
2. Instrument ingestion, feature computation, model scoring, strategy scans, news analysis, paper trading and reporting with separate queues/limits and per-job cost counters.
3. Per-provider quotas: configured central limiter, source entitlement, retries only for idempotent reads, backoff/jitter, quota alerts, graceful stale/abstain.
4. Model scheduler uses model activation state, regime, candidate ranking, priority budgets, deduplicated feature hashes and batch inference. No compulsory continuous 18-model invocation.
5. Database partitioning/indexes based on measured write volume; bounded hot-cache, TTL according to semantic freshness, S3 lifecycle, Postgres WAL/backups and restore rehearsal.
6. Alerts: stale feed, credentials/session expiry, missing bars, wrong instrument mapping, stalled jobs, source revision, model error/latency, stale positions, reconciliation gap, stop failure, monthly spend thresholds.
7. Security: Cognito subject binding; secrets only in approved AWS secret stores; TLS; IAM least privilege; EC2 SSM administration; audit; signed model artefacts; SAST/dependency scans; backup encryption.
8. No invented budgets. Add an explicit USD/INR monthly TCO spreadsheet from actual EC2, gp3, EIP, egress, backups, logs, paid provider/API and model usage after measuring. Alert before spend thresholds.
9. Restart/redeploy with safe state recovery, replay cursors and idempotent side effects. Keeping MintTick EC2 running between deploys is the user's current preference; shutdown policy must never be changed implicitly.
10. Data licences and Indian exchange/broker/retail algo terms require explicit current review before production/redistribution. Personal use ≠ unlimited redistribution rights.

## 9. Phase dependency graph

```text
P0 Baseline/contract map
  -> P1 Identity/calendar/instruments/data entitlement
  -> P2 Live quotes/candles/quality + official filings
  -> P3 Point-in-time feature DAG
  -> P4 Strategy registry + scan/decision ledger
  -> P5 Account/portfolio/risk/execution separation
  -> P6 Research/backtest labels and champion tabular/HMM/anomaly models
  -> P7 Ensemble/agents/evidence/regime/Culprit Catcher
  -> P8 Bind all 42 Flutter screens to production read APIs
  -> P9 Paper/shadow prospective soak, incident drills
  -> P10 Staged operator review + optional separately-authorized LIVE canary
  -> P11 Advanced fundamentals/F&O/forecasters/NLP/learned/RL research
  -> P12 Multi-user enablement only when product/regulatory/entitlements require
```

Dependencies may run in parallel where sources and contracts are frozen: P1/P2 data definitions with UI API mock contract; P5 account guard with P4; P8 UI visual parity work with P2-7. Do not turn planned phases into "complete" without checkable evidence.

## 10. Detailed PR/phase execution rule

Each vertical slice must:
1. name the requirement/route/model/strategy IDs affected and reference this plan;
2. inventory existing code and migrations; implement additive changes, never duplicate working subsystem;
3. specify request/response, schema, data/provenance, auth, freshness, failures, quota and transaction boundary;
4. implement schema/index changes and backward compatible read path before enabling a new producer;
5. write unit, integration, property/invariant and regression tests (include stale/no-data/clock skew/duplicate/unauthorized);
6. capture performance and query/CPU/heap/API cost on realistic datasets; declare measured p50/p95 and spend;
7. demonstrate paper/shadow no-order boundaries and risk veto/stop invariants;
8. run relevant Flutter analyze/widgets/goldens, backend tests, Python parity/worker, security and release gates;
9. enable by default-disabled feature flag where sensitive; document rollback, replay and operational dashboard;
10. link PR, CI run, migration, screenshots and endpoint evidence in the phase acceptance checklist.

No merged documentation page is evidence that a backend feature is implemented. No CI-green stub is evidence that a production data source is live.

## 11. Definition of "engine implemented" (release objective)

- Market universe instrument master, index/holiday/corporate-action identity and source entitlements are verified and refreshable; quote/candle completeness is observable with fail-closed freshness.
- Core market/sector/regime/technical features produced with versioned causal cutoff and reproducible snapshot; source revisions audited.
- At least one **validated** strategy from a manageable subset runs deterministic backtest -> prospective paper -> shadow with measured after-cost outcomes; disabled templates stay labeled.
- Primary model roster deployed with locked artefacts and tested real inference, comparison to non-ML baselines, calibration and bounded abstention; 18 named records correctly state actual maturity.
- Every affected screen uses production read DTOs or explicitly marked unavailable/not implemented; zero fabricated data in production mode, zero hidden synthetic fallback.
- Every research decision produces lineage (input/source hashes, feature/model/policy versions, timestamps, uncertainty, veto and eventual outcome) sufficient for accurate replay and Culprit Catcher.
- Account isolation, order custody, risk checks, broker session boundaries, stop controls and paper reconciliation proven by tests. LIVE still off until approved through separate controlled process.
- Restore rehearsal, alarms, rate/entitlement contracts, performance, cost budget and device tests have links to evidence.
- Future multi-user readiness is demonstrated with at least two synthetic tenants and cross-tenant isolation tests without enabling public onboarding.

## 12. Source of truth / change control

- **GitHub Markdown on dev is the implementation-grade, diffable canonical specification.** Notion companion pages are human-friendly views; edit GitHub first or reconcile both in a reviewed PR.
- For each phase, append actual PR IDs, commit SHA, test IDs, deployment IDs, measured metrics and blockers. State unverified items explicitly.
- The older V4 Notion plan is historical governance context. If a plan conflicts with a verified, newer Java safety contract, preserve the stronger safety invariant and open an explicit architectural decision record.
- Before development, refresh the latest dev tree, deployed AWS posture, provider terms and source licences; do not trust this 2026-11-09 point-in-time snapshot indefinitely.

## 13. Execution start — first small, reversible PRs

1. P0-A: source-level dependency census for all 42 routes and 18 records; mark mock, real, dormant, planned. Export contract inventory and feature flags.
2. P0-B: canonical market/source/time/quality DTOs and typed Flutter repository interfaces (no switch to real execution).
3. P1-A: canonical instrument/alias/calendar schema compatibility audit with migrations V4, V6, V14, V48, V63-69 and contract tests.
4. P2-A: owner feed connected test / quote+source freshness metric plus rate limiter and stale policy; one real quote screen slice, verified without displaying fallback mock.
5. P3-A: incremental EMA/RSI/ATR/VWAP feature DAG for small active universe with versioned cutoff and deterministic recomputation.
6. P4-A: one fully eligible cash-equity strategy: signal -> decision evidence -> WAIT/ABSTAIN -> paper shadow; zero LIVE authority.
7. P6-A: LightGBM vs deterministic technical baseline using fixed point-in-time data and blinded walk-forward evaluation.
8. P8-A: connect Mind Overview and Evidence Ledger first as vertical end-to-end views, then fan out typed read models to remaining 40 routes.

**Implementation change authorization:** this plan requests planning/documentation only. Code, infrastructure, broker or LIVE changes remain separately governed by relevant approvals and safety gates.
