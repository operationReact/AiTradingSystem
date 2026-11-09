# Mintick Mind — Canonical Data Model, Schema, Temporal Lineage and Storage Design

**Baseline:** Audit current `dev` including merged [PR #656](https://github.com/operationReact/AiTradingSystem/pull/656). Flyway migrations **V1–V71** exist. Existing V70/V71 snapshot tables MUST be reused. **This document is a schema proposal, not executable SQL or proof of deployed migrations.** Next migration version must be determined from latest `dev` at implementation time (not reserved blindly).

## 1. Strict ownership boundary

- SHARED_MARKET: provider entitlements, instruments, quotes, candles, index/breadth, corporate actions, official reports; no user IDs on canonical public time series.
- SHARED_RESEARCH: validated point-in-time features, strategy definitions, model artefacts, market predictions, market outcome labels and generic decision evidence; only reusable when source licence permits.
- ACCOUNT_PRIVATE: identity, broker sessions, trading rooms, capital, balances, holdings, risk policy, orders/fills and personal execution learning; owner derived from authenticated subject, future tenant ID ready.
- PLATFORM_CONTROL: source entitlements, data freshness, model promotion, cost ceilings, job health, audit logs and emergency controls.
- A research/model service **cannot** hold order submission authority. Risk and order approval are deterministic Java account-scoped services. No user portfolio is needed to produce generic stock market predictions.

## 2. Canonical timestamp contract

- `exchange_at`: exchange event observation UTC timestamp, with instrument/segment trading session.
- `received_at`: platform ingress UTC timestamp and source/provider, including transport latency.
- `published_at`: when a document/filing/report became publicly available, distinct from fiscal period or ex-date.
- `ingested_at`: DB commit time, distinct from publication.
- `available_at`: earliest instant a feature/model/strategy is allowed to consume that record in a reproducible simulation; requires enough source latency.
- `feature_asof`: last event timestamp included, always <= `available_at` for admissible features.
- `label_matures_at`: earliest time outcome becomes evaluable. Labels MUST NOT join into historical decisions before this.
- `created_at/updated_at`: administrative row lifecycle, not market event time.
- Indian calendar/session boundaries in `Asia/Kolkata`; persist instants in UTC `TIMESTAMPTZ`. Handle opening/closing auctions, partial holidays, special sessions, circuit/suspensions.
- Any future quote, missing field, document published after decision, or backtest using revised data before publication => flag data quality and abstain or exclude.

## 3. Data domain dictionary (D01–D16)

| ID | Data products and core fields | Frequency | Critical quality and entitlement |
|---|---|---|---|
| D01 | Canonical instrument ID, ISIN/venue/segment, aliases, broker token, contract validity, lot/tick, index membership, trading calendar | Daily + expiry/event | Stable identity, history not overwritten |
| D02 | LTP, daily OHLC, cumulative vol, bid/ask/depth, exchange/receive timestamps, feed status, source | Bounded streaming for subscribed universe | Valid tick, heartbeat, staleness and quote status |
| D03 | Open/high/low/close/volume, bar bounds, raw/adjusted flag, correction revision, backfill gaps | On completed 1m/5m/daily bar | Deterministic closing rule, splits/bonus adjustment, duplicates |
| D04 | Index snapshots, India VIX, Nifty 50 breadth, sector ranking, advance/decline and membership | Live where licensed + daily | Constituents point-in-time, stored != live |
| D05 | Quarterly/annual reports, audited/unaudited, consolidated/standalone, revenue, EBIT, PAT, EPS, debt, FCF, valuation | When official release published | Document lineage, restatement, release lag |
| D06 | Dividends, bonus, splits, rights, board/AGM, pledge, management, corporate action adjustment | Event-driven | Ex-date and publish-time distinguished |
| D07 | FII/DII provisional/final daily aggregates, participant OI, volume/delivery | Official daily/weekly | No invented stock-level institutional trades |
| D08 | RBI repo, CPI, GDP, 10Y yields, USD/INR, crude, global risk/calendar | Release-driven | Revisions and release times tracked |
| D09 | News/article/filing metadata, entity links, text hash, licence, factual extraction with source URL | New report/event | Deduplicate, prompt-injection resistance, LLM cost |
| D10 | Futures/options contract, strike/right/expiry, bid/ask, OI, IV, Greeks, margin, lot/settlement | Selective when module active | Quote alignment, contract identity, pricing caveats |
| D11 | Features, definitions, window/version, input fingerprint, quality, as-of/available-at | On completed bar/new event | Shared DAG, no lookahead |
| D12 | Model registry/version, artifact hash, training data fingerprint, evaluation, predictions, latency/cost | Selected batch/inference | Held-out validated; licence; research-only gate |
| D13 | Strategy rule/template/version, parameters, eligibility, signals and abstain reasons | Horizon/trigger | Template!=active strategy; after-cost evidence |
| D14 | Broker account, capital, rooms, positions, orders, fills, fees, margins, owner-scoped limits | Broker events and reconciliation | Cross-account isolation, idempotency, no stale risk approval |
| D15 | Market outcome labels, paper/shadow trades, real fill outcomes, evaluation runs, incidents | Horizon maturity / end of run | Market alpha vs execution outcomes separated |
| D16 | Provider entitlement, source/job health, audit, feature readiness and cost meters | Ongoing | Quota/licence enforcement and observability |

## 4. Confirmed existing PR #656 schema — reuse, do not recreate

**`backend/src/main/resources/db/migration/V70__market_overview_snapshot.sql`**

```sql
CREATE TABLE IF NOT EXISTS market_overview_snapshot (
  universe_code VARCHAR(32) PRIMARY KEY,
  generated_at TIMESTAMPTZ NOT NULL,
  payload JSONB NOT NULL
);
```

**`backend/src/main/resources/db/migration/V71__market_detail_snapshot.sql`**

```sql
CREATE TABLE IF NOT EXISTS market_detail_snapshot (
  instrument_id BIGINT PRIMARY KEY,
  fetched_at TIMESTAMPTZ NOT NULL,
  payload JSONB NOT NULL
);
```

Existing Java owners are `MarketOverviewSnapshotStore`, `MarketDetailSnapshotStore`, `MarketOverviewService`, `CustomerMarketDataController`. Existing Flutter consumer: `CustomerMarketOverview` + `CustomerMarketIdea` in the Markets repository.

**Existing semantics to preserve:**
- Market overview in-process cache TTL **30 seconds**, with verified provider compute followed by a best-effort snapshot write.
- When live computation fails, return a stored overview labelled **`STORED_LAST_OVERVIEW`**, with indices converted to `LAST_CLOSE`. Stored ideas must be **empty**, never actionable.
- Stock detail persisted when genuinely obtained; on fallback label **`STORED_LAST_DETAIL`**, `LAST_CLOSE` rather than `LIVE`; preserve quote observation time.
- Index `LIVE` requires cash session, timestamp after session opening, no future tick, and quote age at most **two minutes**. Market ideas similarly require a valid in-session quote not older than two minutes.
- Existing screened idea `signal` values `BREAKOUT`, `MOMENTUM`, `GAP_UP`; priority 52-week high breakout, then 2%+ momentum, then 1%+ opening gap, all **rule-screen hints**, not model decisions or broker orders.
- Existing `MarketSnapshotMigrationIT` tests V70/V71 on ephemeral real PostgreSQL and verifies JSONB round-trip/idempotent upserts; Phase 8 completion workflow includes it.
- **Design gap:** V70/V71 JSONB are *latest-reported snapshots*, not append-only historical market data, not canonical normalized candle tables, and not full event/feature/decision lineage. They should feed a separate Mind read adapter, with known freshness state; do **not** turn these into a general time-series warehouse.

### Additional PR #656 integration design
1. Build `MarketOverviewReadAdapter` wrapping existing `MarketOverviewService` with reference to existing `V68` Nifty/sector definitions and V70, not a second fetching job.
2. Build `StockDetailReadAdapter` wrapping existing controller/store V71. Preserve source status and `exchange_at` to all Mind consumers.
3. Reuse a canonical `MarketIdea` read DTO for BREAKOUT/MOMENTUM/GAP_UP screening only. Promote to Mintick Mind strategy candidate **only after separate** feature/validation/decision ledger and risk gating.
4. Avoid displaying stored ideas in any Mind screen when Markets already suppresses them. If a historical stored idea is needed for evaluation, it must come from a **separate immutable prospective signal-event ledger**, not V70 snapshots.
5. **No new duplicate tables** such as `mind_market_overview` or `mind_market_detail` for the same latest universe/instrument payload.
6. Source-status normalization table: `LIVE` valid current verified provider tick; `LAST_CLOSE` historical last quote; `STORED_LAST_OVERVIEW` fallback persisted overview; `STORED_LAST_DETAIL` fallback stock detail; `STALE` expired current session; `UNKNOWN/UNAVAILABLE` insufficient data. A stored snapshot is not a live trading predicate.
7. Validate scenario: fresh V70 source but aged individual member -> exclude aged member from actionable candidates; universal freshness cannot replace per-row timestamps.

## 5. Proposed future tables (logical, subject to existing migration reconciliation)

| Domain | Existing sources to inspect | Proposed additive entity / extension | Critical keys/indexes |
|---|---|---|---|
| Reference | V4–V6, V48, InstrumentMasterRepository | versioned `provider_instrument_alias`, `index_membership_history`, `corporate_action_adjustment` if missing | provider/token/valid_from, ISIN, instrument/asof |
| Quotes/candles | V8, V68–V71, MarketDataRepository | `quote_quality_event`, `candle_revision`, not duplicate latest snapshots | instrument/interval/bar_end; source and available_at |
| Official reports | V49 archive, official ingestion | structured `filing_fact_revision`, `institutional_flow_report`, `macro_release` as allowed | report_type/period/published_at/version |
| Features | V60 causal projection | reusable `feature_definition`, `feature_snapshot`, `feature_dependency` contract if missing | instrument/interval/asof/feature_version/input_hash |
| Models | V50–V62 model governance + ledgers | model promotion evidence, `model_prediction` projections if not present | model_version/case_key/input_hash uniqueness |
| Strategies | V9, V17–V18, research scan tables | machine-readable definition/version and dependency eligibility; avoid duplicate strategy table | strategy_id/version/horizon/case |
| Decisions | Existing risk decisions/order audit, V56–V62 prospective evidence | bounded decision trace pointer/outcome label, no duplicate risk ledger | decision_id, source receipt hash, outcome maturity |
| Account | V38–V43, V63–V67, account and order tables | owner binding and future tenant key **only where missing**, protect existing foreign keys | account ID, authenticated subject, room/broker IDs |
| Learning | Paper/shadow, backtest, V56–V62 | separated `market_outcome_label` and `execution_outcome`, replay incident links | event/decision/horizon and maturity cutoff |
| Ops | Existing notifications/audit V35–V37 | provider quota health/cost/feature freshness projections | source/interval/status and job_run ID |

### Example suggested future logical fields (not deployable SQL)
```text
feature_snapshot(
  snapshot_id, instrument_id, interval, feature_schema_version,
  feature_asof, available_at, source_fingerprint, feature_payload_hash,
  quality_status, values_jsonb, created_at
)
model_prediction(
  prediction_id, case_key, model_version_id, feature_snapshot_id,
  output_hash, score, calibration_version, uncertainty,
  as_of, generated_at, processing_ms, source_status
)
market_outcome_label(
  label_id, decision_id, instrument_id, horizon, label_matures_at,
  realized_market_return, benchmark_return, cost_model_id, quality
)
private_execution_outcome(
  tenant_id, user_id, broker_account_id, order_intent_id, fill_id,
  executed_at, commission, taxes, spread, slippage, realized_pnl, reconciliation_id
)
```

## 6. Ingestion pipeline and quality state machine

1. Acquire provider/official-source licence or account entitlement, quota and auth; never pass personal broker execution authority to shared data service.
2. Register source/exchange/segment and instrument catalogue provenance.
3. Receive tick/batch/document with raw source reference and multiple timestamps; reject impossible future skew, negative price, invalid symbol or corrupted payload.
4. Normalize provider fields and canonical instrument; version aliases and corporate actions.
5. Deduplicate idempotently by source/event identity; detect out-of-order and missing bursts; store immutable quality incident on bad data.
6. Persist trusted latest snapshot to existing market tables/stores; capture raw evidence reference where permitted.
7. Build completed-bar aggregates and reconcile official EOD; mark partial/corrected candles and re-run downstream feature hashes.
8. Trigger affected incremental features on causal close/event; cache by `instrument+timeframe+feature_version+source_cutoff+input_hash`.
9. Route bounded strategy scan and qualified model inference; retain source, feature, policy and receipt hashes before making decision.
10. Evaluate market outcome only after label maturity. Record paper/shadow and user execution outcomes in **separate data products**.
11. Serve UI from read projections with explicit `source_status`, last observed market time and generated time; no cross-screen invisible mock fallback.
12. Housekeeping: repair gaps, provider reconciliation, eviction, TTL, archival/lifecycle rules and audit.

### Freshness classes (do not collapse them)
`VERIFIED_LIVE` if source/market/session/per-row timestamps all satisfy live predicate; `STORED_LAST` persisted but not live; `PARTIAL` subset current or incomplete; `STALE` source old within market session; `MARKET_CLOSED`; `UNAVAILABLE`; `UNAUTHORIZED`; `SYNTHETIC_DEMO`. Each class carries `last_exchange_at`, `last_received_at`, `source`, `universe`, `reason`. A research data product should not automatically inherit a two-minute quote TTL: e.g., an official EOD FII report has session-specific freshness and publication semantics.

## 7. Storage and cost strategy

- **Hot:** bounded process-local LRU latest quote/screen projections. Redis only when multiple instances and actual fan-out create a measurable benefit.
- **Warm:** PostgreSQL indexed candles, metadata, account/risk, features under selective retention and important research outputs. Partition only if measurements justify; ensure consistent snapshot backups.
- **Cold:** S3 encrypted/parquet compressed historical bars, bulk official report files and reproducible research dataset manifests. No broker credentials/log PII in raw archives. Verify source retention licence.
- **History selection:** proposed liquid 500 daily universe, 200 rotating 5-min, 50 live/1-min + held positions/indices; not promises of provider access. Avoid all-token streaming and 24/7 model polling.
- **Batch model inference:** event/bar completion, once per feature fingerprint and model version; batch qualified active instruments. News LLM summarization once per licensed document/content hash.
- **Database:** batch writes, query pagination, prepared SQL and single-producer idempotency; asynchronous outbox for essential events, not independent per-screen polling loops.
- **Budget:** measure provider quotas/paid data; EC2 vCPU/RAM/EBS utilization; DB disk and WAL; S3 retrieval/storage; egress; API/LLM tokens; CloudWatch and backup. Set budget alarms based on real account values, not invented figures.
- **Restore:** documented Postgres backup recovery, S3 object integrity, gap replay and paper order idempotency during crash recovery.

## 8. API read/write contract

```json
{
  "contractVersion": "mind-read.v1",
  "scope": "MARKET",
  "asOf": "2026-11-09T04:00:00Z",
  "receivedAt": "2026-11-09T04:00:01Z",
  "generatedAt": "2026-11-09T04:00:02Z",
  "sourceStatus": "STORED_LAST",
  "source": "STORED_LAST_OVERVIEW",
  "availableAt": "2026-11-09T04:00:01Z",
  "data": {},
  "warnings": ["Stored snapshot: not current streaming evidence"],
  "traceId": "server-generated-trace"
}
```

- JSON is illustrative contract shape; **never** emit example timestamps as genuine live price evidence.
- Endpoints proposed under `/api/mind/v1` are not yet implemented. Reuse existing Spring Markets/Research APIs via adapters where semantically compatible; typed OpenAPI and versioned DTO contract tests.
- Account endpoint keys and private read models are server-bound to Cognito subject; no client-supplied owner override.
- Stateful commands require authenticated operator eligibility, CSRF/security controls as applicable, idempotency UUID, optimistic version, and append-only audit.
- Streaming uses bounded subscription fan-out with auth/entitlement; on disconnect/expired status resume read snapshots and invalidate risk readiness.
- A row in V70/V71 can display as stored last only; no reactivation of prior idea lists.

## 9. Migration and backward-compatibility runbook

- Pull latest `dev` and list all `backend/src/main/resources/db/migration/V*` before assigning the next migration number. After PR #656 this was **V71**, but newer PRs may have advanced it.
- For each feature: map old table/DTO and query shape; propose additive schema; test new SQL on clean real Postgres and database upgraded from latest production-compatible prior schema; avoid table-renames/deletes.
- Write migration integration tests proving Flyway history, foreign keys, idempotent writes, serialized JSON version compatibility, report/source revision handling and query plan indexes.
- Handle application rolling compatibility (old server reading new table/new server with old data) where deployment strategy requires; only remove obsolete structures after explicit cleanup phase.
- Do not declare migrations deployed until verified in the actual AWS backend environment. GitHub merge ≠ production migration applied.
- Keep original V70/V71 table definitions unchanged. Any new longevity, history or provenance use a separate table/projection and domain service.

## 10. Schema completion criteria

- All 16 data domains have source/ownership/schema, point-in-time timestamps, health, quality and retention policy.
- Confirm identical canonical market readings for Markets and Mintick Mind, including PR #656 stored fallback and no stored ideas.
- Demonstrate two synthetic user accounts cannot read/write the other's private holdings, orders, notes, cost basis, broker credentials or risk controls.
- Real PostgreSQL migration tests, backfill/restart/resume, indexing, corrupt JSON handling, session boundary and provider-outage drills pass.
- Each source is mapped to allowed datasets and usage/redistribution entitlement.
